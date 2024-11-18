/*global VuFind, multiFacetsSelectionEnabled */

/**
 * Returns if multiFacetsSelectionEnabled is set. Fallback if the value is missing for false
 *
 * @type {Function} Function to check for multiFacetsSelectionEnabled
 */
let isMultiFacetsSelectionEnabled = () => {
  if (typeof multiFacetsSelectionEnabled === "undefined") {
    return false;
  }
  return multiFacetsSelectionEnabled;
};

/* --- Facet List --- */
VuFind.register('facetList', function FacetList() {
  function getCurrentContainsValue() {
    const containsEl = document.querySelector('.ajax_param[data-name="contains"]');
    return containsEl ? containsEl.value : null;
  }

  function setCurrentContainsValue(val) {
    const containsEl = document.querySelector('.ajax_param[data-name="contains"]');
    if (containsEl) {
      containsEl.value = val;
    }
  }

  function overrideHref(selector, overrideParams = {}) {
    $(selector).each(function overrideHrefEach() {
      const dummyDomain = 'https://www.example.org'; // we need this since the URL class cannot parse relative URLs
      let url = new URL(dummyDomain + $(this).attr('href'));
      Object.entries(overrideParams).forEach(([key, value]) => {
        url.searchParams.set(key, value);
      });
      url = url.href;
      url = url.replaceAll(dummyDomain, '');
      $(this).attr('href', url);
    });
  }

  function updateHrefContains() {
    const overrideParams = { contains: getCurrentContainsValue() };
    overrideHref('.js-facet-sort', overrideParams);
    overrideHref('.js-facet-next-page', overrideParams);
    overrideHref('.js-facet-prev-page', overrideParams);
  }

  function getContent(overrideParams = {}) {
    const ajaxParams = $('.ajax_params').data('params');
    let url = ajaxParams.urlBase;

    for (let [key, val] of Object.entries(ajaxParams)) {
      if (key in overrideParams) {
        val = overrideParams[key];
      }
      url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(val);
    }

    const contains = getCurrentContainsValue();
    if (contains) {
      url += '&contains=' + encodeURIComponent(contains);
    }

    if (!("facetsort" in overrideParams)) {
      const sort = $('.js-facet-sort.active').data('sort');
      if (sort !== undefined) {
        url += '&facetsort=' + encodeURIComponent(sort);
      }
    }

    url += '&ajax=1';

    return Promise.resolve($.ajax({
      url: url
    }));
  }

  function updateContent(overrideParams = {}) {
    $('#facet-info-result').html(VuFind.loading());
    getContent(overrideParams).then(html => {
      let htmlList = '';
      $(VuFind.updateCspNonce(html)).find('.full-facet-list').each(function itemEach() {
        htmlList += $(this).prop('outerHTML');
      });
      $('#facet-info-result').html(htmlList);
      updateHrefContains();
      VuFind.lightbox_facets.setup();
    });
  }

  // Useful function to delay callbacks, e.g. when using a keyup event
  // to detect when the user stops typing.
  // See also: https://stackoverflow.com/questions/1909441/how-to-delay-the-keyup-handler-until-the-user-stops-typing
  var inputCallbackTimeout = null;
  function registerCallbacks() {
    $('.facet-lightbox-filter').removeClass('hidden');

    $('.ajax_param[data-name="contains"]').on('input', function onInputChangeFacetList(event) {
      clearTimeout(inputCallbackTimeout);
      if (event.target.value.length < 1) {
        $('#btn-reset-contains').addClass('hidden');
      } else {
        $('#btn-reset-contains').removeClass('hidden');
      }
      inputCallbackTimeout = setTimeout(function onInputTimeout() {
        updateContent({ facetpage: 1 });
      }, 500);
    });

    $('#btn-reset-contains').on('click', function onResetClick() {
      setCurrentContainsValue('');
      $('#btn-reset-contains').addClass('hidden');
      updateContent({ facetpage: 1 });
    });
  }

  function setup() {
    if ($.isReady) {
      registerCallbacks();
    } else {
      $(function ready() {
        registerCallbacks();
      });
    }
  }

  return { setup: setup, getContent: getContent, updateContent: updateContent };
});

/* --- Multi Facets Handling --- */
VuFind.register('multiFacetsSelection', function multiFacetsSelection() {
  const globalAddedParams = new URLSearchParams();
  const globalRemovedParams = new URLSearchParams();
  const initialParams = new URLSearchParams();
  let rangeSelectorForms = [];
  let isMultiFacetsSelectionActivated = false;
  let callbackOnApply;
  let callbackWhenDeactivated;
  let defaultContext;
  // Events to emit
  const activation_event = 'facet-selection-begin';
  const deactivation_event = 'facet-selection-cancel';
  const apply_event = 'facet-selection-done';

  /**
   * Normalize a filter value
   *
   * @param {string} key   Parameter name
   * @param {string} value Value
   *
   * @returns string
   */
  function normalizeValue(key, value) {
    if (key !== 'filter[]') {
      return value;
    }
    const p = value.indexOf(':');
    if (p < 0) {
      return value;
    }
    // Ensure that filter value is surrounded by quotes
    let filterValue = value.substr(p + 1);
    filterValue = (!filterValue.startsWith('"') ? '"' : '') + filterValue + (!filterValue.endsWith('"') ? '"' : '');
    return value.substr(0, p) + ':' + filterValue;
  }

  for (const [key, value] of (new URLSearchParams(window.location.search))) {
    initialParams.append(key, normalizeValue(key, value));
  }

  // Make sure NOT to have a specific range filter parameter in the final URL
  function hideRangeFilterFromFinalUrl(rangeName) {
    const paramStart = encodeURIComponent(rangeName + ':');
    for (const [value] of initialParams.getAll('filter[]')) {
      if (value.startsWith(paramStart)) {
        globalRemovedParams.push(encodeURI('filter[]=' + value));
        return;
      }
    }
    for (const [value] of globalAddedParams.getAll('filter[]')) {
      if (value.startsWith(paramStart)) {
        globalAddedParams.remove('filter[]', value);
      }
    }
  }

  // For every date range selector, does a routine to deal with URL parameters
  function handleRangeSelector() {
    let addedRangeParams, rangeParams, allEmptyRangeParams;
    const currentQueryParams = new URLSearchParams(window.location.search);

    for (const form of rangeSelectorForms) {
      addedRangeParams = [];
      rangeParams = [];
      allEmptyRangeParams = true;
      const dateInputs = form.querySelectorAll('.date-fields input');
      for (const input of dateInputs) {
        if (currentQueryParams.has(input.name)) {
          // Update existing parameter
          initialParams.set(input.name, input.value);
        } else {
          addedRangeParams.push([input.name, input.value]);
        }
        rangeParams.push(input.name);
        if (input.value !== '') {
          allEmptyRangeParams = false;
        }
      }

      // Handle the range input hidden fields
      const rangeName = form.dataset.name;
      const rangeFilterField = form.dataset.filterField;
      if (allEmptyRangeParams) {
        // Only empty fields; remove the parameter
        for (const [key, value] of addedRangeParams) {
          globalRemovedParams.append(key, value);
        }
        hideRangeFilterFromFinalUrl(rangeName);
      } else {
        // Some values present; add values for the final URL
        for (const [key, value] of addedRangeParams) {
          globalAddedParams.append(key, value);
        }
        if (!currentQueryParams.has(rangeFilterField, rangeName)) {
          globalAddedParams.append(rangeFilterField, rangeName);
          globalRemovedParams.delete(rangeName);
          hideRangeFilterFromFinalUrl(rangeName);
        }
      }
    }
  }

  // Goes through all modified facets to compile into 2 arrays of added and removed URL parameters
  function setModifiedFacets() {
    let elems = document.querySelectorAll('[data-multi-filters-modified="true"]');

    for (const elem of elems) {
      const href = elem.getAttribute('href');
      const p = href.indexOf('?');
      const elemParams = new URLSearchParams(p >= 0 ? href.substring(p + 1) : '');

      // Add parameters that did not initially exist:
      for (const [key, value] of elemParams) {
        if (!initialParams.has(key, value)) {
          globalAddedParams.append(key, value);
        }
      }
      // Remove parameters that this URL no longer has:
      for (const [key, value] of initialParams) {
        if (!elemParams.has(key, value)) {
          globalRemovedParams.append(key, value);
        }
      }
    }
  }

  // Compile current parameters and newly added / removed to return the URL to redirect to
  function getHrefWithNewParams() {
    setModifiedFacets();
    handleRangeSelector();

    const newParams = new URLSearchParams(initialParams);
    // Remove parameters:
    for (const [key, value] of globalRemovedParams) {
      newParams.delete(key, value);
    }
    for (const [key, value] of globalAddedParams) {
      newParams.append(key, value);
    }
    // Take base url from data attribute if present (standalone full facet list):
    const baseUrl = defaultContext.dataset.searchUrl || window.location.pathname;
    return baseUrl + '?' + newParams.toString();
  }

  function applyMultiFacetsSelection() {
    defaultContext.getElementsByClassName('js-apply-multi-facets-selection')[0]
      .removeEventListener('click', applyMultiFacetsSelection);
    if (callbackOnApply instanceof Function) {
      callbackOnApply();
    }
    const params = {
      url: getHrefWithNewParams()
    };
    VuFind.emit(apply_event, params);
    window.location.assign(params.url);
  }

  function toggleSelectedFacetStyle(elem) {
    if (elem.classList.contains('exclude')) {
      elem.classList.toggle('selected');
    } else {
      let facet;
      if (elem.classList.contains('facet')) {
        facet = elem;
      } else {
        facet = elem.closest('.facet');
      }
      if (!facet.parentElement.classList.contains('checkboxFilter')) {
        facet.classList.toggle('active');
      }

      let icon = elem.closest('a').querySelector('.icon');
      if (icon !== null) {
        const newCheckedState = icon.dataset.checked === 'false';
        let attrs = {};
        attrs.class = 'icon-link__icon';
        attrs['data-checked'] = (newCheckedState ? 'true' : 'false');
        icon.outerHTML = VuFind.icon(newCheckedState ? 'facet-checked' : 'facet-unchecked', attrs);
      }
    }
  }

  function handleMultiSelectionClick(e) {
    e.preventDefault();
    let elem = e.currentTarget;

    // Switch data-multi-filters-modified to keep track of changed facets
    const currentAttrVal = elem.getAttribute('data-multi-filters-modified');
    const isOriginalState = currentAttrVal === null || currentAttrVal === 'false';
    if (isOriginalState && elem.closest('.facet').querySelectorAll('[data-multi-filters-modified="true"]').length > 0) {
      elem.closest('.facet').querySelector('[data-multi-filters-modified="true"]').click();
    }
    elem.setAttribute('data-multi-filters-modified', isOriginalState);
    toggleSelectedFacetStyle(elem);
  }

  function toggleMultiFacetsSelection(enable) {
    if (typeof enable !== 'undefined') {
      isMultiFacetsSelectionActivated = enable;
    }
    document.querySelectorAll('.multi-facet-selection').forEach( el => el.classList.toggle('multi-facet-selection-active', isMultiFacetsSelectionActivated) );
    let checkboxes = document.getElementsByClassName('js-user-selection-multi-filters');
    for (let i = 0; i < checkboxes.length; i++) {
      checkboxes[i].checked = isMultiFacetsSelectionActivated;
    }
    if (!isMultiFacetsSelectionActivated) {
      const elems = document.querySelectorAll('[data-multi-filters-modified="true"]');
      for (const elem of elems) {
        elem.setAttribute('data-multi-filters-modified', "false");
        toggleSelectedFacetStyle(elem);
      }
    }
    const event = isMultiFacetsSelectionActivated ? activation_event : deactivation_event;
    VuFind.emit(event);
  }

  function registerCallbackOnApply(callback) {
    callbackOnApply = callback;
  }

  function registerCallbackWhenDeactivated(callback) {
    callbackWhenDeactivated = callback;
  }

  function handleClickedFacet(e) {
    if (isMultiFacetsSelectionActivated === true) {
      handleMultiSelectionClick(e);
    } else if (callbackWhenDeactivated instanceof Function) {
      callbackWhenDeactivated();
    }
  }

  function initMultiFacetControls(context) {
    // Listener on checkbox for multiFacetsSelection feature
    const activationElem = context.querySelector('.js-user-selection-multi-filters');
    if (activationElem) {
      activationElem.addEventListener('change', function multiFacetSelectionChange() { toggleMultiFacetsSelection(this.checked); } );
    }
    // Listener on apply filters button
    const applyElem = context.querySelector('.js-apply-multi-facets-selection');
    if (applyElem) {
      applyElem.addEventListener('click', applyMultiFacetsSelection);
    }
  }

  function initFacetClickHandler(context) {
    context.classList.add('multi-facet-selection');
    context.querySelectorAll('a.facet:not(.narrow-toggle):not(.js-facet-next-page), .facet a').forEach(function addListeners(link) {
      link.addEventListener('click', handleClickedFacet);
    });
  }

  // List all the forms for date range facets and add a listener on them to prevent submission
  function initRangeSelection(context) {
    context.querySelectorAll('div.facet form .date-fields').forEach((elem) => {
      const formElement = elem.closest('form');
      if (formElement && !rangeSelectorForms.includes(formElement)) {
        rangeSelectorForms.push(formElement);
        formElement.addEventListener('submit', function rangeFormSubmit(e) {
          if (isMultiFacetsSelectionActivated) {
            e.preventDefault();
          }
        });
      }
    });
  }

  function init(_context) {
    if (!isMultiFacetsSelectionEnabled()) {
      return;
    }
    if (defaultContext === undefined) {
      defaultContext = document.getElementById('search-sidebar');
      if (null === defaultContext) {
        // No sidebar, we may be on the standalone full facet list page:
        defaultContext = document.querySelector('.js-full-facet-list');
        if (null === defaultContext) {
          // No context:
          return;
        }
      }
    }
    const context = (typeof _context === "undefined") ? defaultContext : _context;
    initMultiFacetControls(context);
    initFacetClickHandler(context);
    initRangeSelection(context);
    // Synchronize the state of multi-facet checkboxes in case there's e.g. a lightbox with its own controls:
    VuFind.multiFacetsSelection.toggleMultiFacetsSelection();
  }

  return {
    init: init,
    registerCallbackOnApply: registerCallbackOnApply,
    registerCallbackWhenDeactivated: registerCallbackWhenDeactivated,
    toggleMultiFacetsSelection: toggleMultiFacetsSelection,
    initFacetClickHandler: initFacetClickHandler
  };
});

/* --- Side Facets --- */
VuFind.register('sideFacets', function SideFacets() {
  function showLoadingOverlay() {
    let elem;
    if (this === undefined || this.nodeName === undefined) {
      elem = $('#search-sidebar .collapse, .checkbox-filters');
    } else {
      elem = $(this).closest(".collapse");
    }
    elem.append(
      '<div class="facet-loading-overlay">'
      + '<span class="facet-loading-overlay-label">'
      + VuFind.loading()
      + '</span></div>'
    );
  }

  function activateFacetBlocking(context) {
    let finalContext = (typeof context === "undefined") ? $(document.body) : context;
    finalContext.find('a.facet:not(.narrow-toggle):not(.js-facet-next-page),.facet a').click(showLoadingOverlay);
  }

  function activateSingleAjaxFacetContainer() {
    var $container = $(this);
    var facetList = [];
    var $facets = $container.find('div.collapse.in[data-facet], div.collapse.show[data-facet], .checkbox-filters [data-facet]');
    $facets.each(function addFacet() {
      if (!$(this).data('loaded')) {
        facetList.push($(this).data('facet'));
      }
    });
    if (facetList.length === 0) {
      return;
    }
    const querySuppressed = $container.data('querySuppressed');
    let query = window.location.search.substring(1);
    if (querySuppressed) {
      // When the query is suppressed we can't use the page URL directly since it
      // doesn't contain the actual query, so take the full query and update any
      // parameters that may have been dynamically modified (we deliberately avoid)
      // touching anything else to avoid encoding issues e.g. with brackets):
      const storedQuery = new URLSearchParams($container.data('query'));
      const windowQuery = new URLSearchParams(query);
      ['sort', 'limit', 'page'].forEach(key => {
        const val = windowQuery.get(key);
        if (null !== val) {
          storedQuery.set(key, val);
        } else {
          storedQuery.delete(key);
        }
      });
      query = storedQuery.toString();
    }
    var request = {
      method: 'getSideFacets',
      searchClassId: $container.data('searchClassId'),
      location: $container.data('location'),
      configIndex: $container.data('configIndex'),
      querySuppressed: querySuppressed,
      extraFields: $container.data('extraFields'),
      enabledFacets: facetList
    };
    $container.find('.facet-load-indicator').removeClass('hidden');
    $.getJSON(VuFind.path + '/AJAX/JSON?' + query, request)
      .done(function onGetSideFacetsDone(response) {
        $.each(response.data.facets, function initFacet(facet, facetData) {
          var containerSelector = typeof facetData.checkboxCount !== 'undefined'
            ? '.checkbox-filters ' : '.facet-group ';
          var $facetContainer = $container.find(containerSelector + '[data-facet="' + facet + '"]');
          $facetContainer.data('loaded', 'true');
          if (typeof facetData.checkboxCount !== 'undefined') {
            if (facetData.checkboxCount !== null) {
              $facetContainer.find('.avail-count').text(
                facetData.checkboxCount.toString().replace(/\B(?=(\d{3})+\b)/g, VuFind.translate('number_thousands_separator'))
              );
            }
          } else if (typeof facetData.html !== 'undefined') {
            $facetContainer.html(VuFind.updateCspNonce(facetData.html));
            if (!isMultiFacetsSelectionEnabled()) {
              activateFacetBlocking($facetContainer);
            }
          }
          if (isMultiFacetsSelectionEnabled()) {
            VuFind.multiFacetsSelection.initFacetClickHandler($facetContainer.get()[0]);
          }
          $facetContainer.find('.facet-load-indicator').remove();
        });
        VuFind.lightbox.bind($('.sidebar'));
        VuFind.emit('VuFind.sidefacets.loaded');
        if (isMultiFacetsSelectionEnabled()) {
          VuFind.multiFacetsSelection.initRangeSelection();
        }
      })
      .fail(function onGetSideFacetsFail() {
        $container.find('.facet-load-indicator').remove();
        $container.find('.facet-load-failed').removeClass('hidden');
      });
  }

  function loadAjaxSideFacets() {
    $('.side-facets-container-ajax').each(activateSingleAjaxFacetContainer);
  }

  function facetSessionStorage(e, data) {
    var source = $('#result0 .hiddenSource').val();
    var id = e.target.id;
    var key = 'sidefacet-' + source + id;
    sessionStorage.setItem(key, data);
  }

  function init() {
    if (isMultiFacetsSelectionEnabled()) {
      VuFind.multiFacetsSelection.registerCallbackOnApply(showLoadingOverlay);
      VuFind.multiFacetsSelection.registerCallbackWhenDeactivated(showLoadingOverlay);
    } else {
      // Display "loading" message after user clicks facet:
      activateFacetBlocking();
    }

    $('.facet-group .collapse').each(function openStoredFacets(index, item) {
      var source = $('#result0 .hiddenSource').val();
      var storedItem = sessionStorage.getItem('sidefacet-' + source + item.id);
      if (storedItem) {
        const oldTransitionState = VuFind.disableTransitions(item);
        try {
          if ((' ' + storedItem + ' ').indexOf(' in ') > -1) {
            $(item).collapse('show');
          } else if (!$(item).data('forceIn')) {
            $(item).collapse('hide');
          }
        } finally {
          VuFind.restoreTransitions(item, oldTransitionState);
        }
      }
    });

    // Save state on collapse/expand:
    let facetGroup = $('.facet-group');
    facetGroup.on('shown.bs.collapse', (e) => facetSessionStorage(e, 'in'));
    facetGroup.on('hidden.bs.collapse', (e) => facetSessionStorage(e, 'collapsed'));

    // Side facets loaded with AJAX
    if (VuFind.getBootstrapMajorVersion() === 3) {
      $('.side-facets-container-ajax')
        .find('div.collapse[data-facet]:not(.in)')
        .on('shown.bs.collapse', loadAjaxSideFacets);
    } else {
      document.querySelectorAll('.side-facets-container-ajax div[data-facet]').forEach((collapseEl) => {
        collapseEl.addEventListener('shown.bs.collapse', loadAjaxSideFacets);
      });
    }
    loadAjaxSideFacets();

    // Keep filter dropdowns on screen
    $(".search-filter-dropdown").on("shown.bs.dropdown", function checkFilterDropdownWidth(e) {
      var $dropdown = $(e.target).find(".dropdown-menu");
      if ($(e.target).position().left + $dropdown.width() >= window.innerWidth) {
        $dropdown.addClass("dropdown-menu-right");
      } else {
        $dropdown.removeClass("dropdown-menu-right");
      }
    });
  }

  return { init: init };
});

/* --- Lightbox Facets --- */
VuFind.register('lightbox_facets', function LightboxFacets() {
  function lightboxFacetSorting() {
    var sortButtons = $('.js-facet-sort');
    function sortAjax(button) {
      var sort = $(button).data('sort');
      VuFind.facetList.updateContent({facetsort: sort});
      $('.full-facet-list').addClass('hidden');
      sortButtons.removeClass('active');
    }
    sortButtons.off('click');
    sortButtons.on('click', function facetSortButton() {
      sortAjax(this);
      $(this).addClass('active');
      return false;
    });
  }

  function setup() {
    if (isMultiFacetsSelectionEnabled()) {
      let elem = document.querySelector('.js-full-facet-list');
      if (elem) {
        VuFind.multiFacetsSelection.init(elem);
      }
    }
    lightboxFacetSorting();
    $('.js-facet-next-page').on("click", function facetLightboxMore() {
      let button = $(this);
      const page = parseInt(button.attr('data-page'), 10);
      if (button.attr('disabled')) {
        return false;
      }
      button.attr('disabled', 1);
      button.html(VuFind.translate('loading_ellipsis'));

      const overrideParams = { facetpage: page, layout: 'lightbox', ajax: 1 };
      VuFind.facetList.getContent(overrideParams).then(data => {
        $(data).find('.js-facet-item').each(function eachItem() {
          button.before($(this).prop('outerHTML'));
        });
        const list = $(data).find('.js-facet-item');
        if (list.length && $(data).find('.js-facet-next-page').length) {
          button.attr('data-page', page + 1);
          button.attr('href', button.attr('href').replace(/facetpage=\d+/, 'facetpage=' + (page + 1)));
          button.html(VuFind.translate('more_ellipsis'));
          button.removeAttr('disabled');
        } else {
          button.remove();
        }
        if (isMultiFacetsSelectionEnabled()) {
          document.querySelectorAll('.full-facet-list')
            .forEach(facetList => VuFind.multiFacetsSelection.initFacetClickHandler(facetList));
        }
      });
      return false;
    });
    const updateFacetListHeightFunc = function () {
      const margin = 230;
      $('#modal .lightbox-scroll').css('max-height', window.innerHeight - margin);
    };
    $(window).on('resize', updateFacetListHeightFunc);
    // Initial resize:
    updateFacetListHeightFunc();
  }

  return { setup: setup };
});

function registerSideFacetTruncation() {
  VuFind.truncate.initTruncate('.truncate-facets', '.facet__list__item');
  // Only top level is truncatable with hierarchical facets:
  VuFind.truncate.initTruncate('.truncate-hierarchical-facets', '> li');
}

VuFind.listen('VuFind.sidefacets.loaded', registerSideFacetTruncation);
