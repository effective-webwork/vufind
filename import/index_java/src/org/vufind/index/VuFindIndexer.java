package org.vufind.index;
/**
 * Custom VuFind indexing routines.
 *
 * Copyright (C) Villanova University 2017.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.StringBuilder;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.*;
import java.text.SimpleDateFormat;

import org.apache.log4j.Logger;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.marc4j.marc.VariableField;
import org.solrmarc.callnum.DeweyCallNumber;
import org.solrmarc.callnum.LCCallNumber;
import org.solrmarc.tools.CallNumUtils;
import org.solrmarc.tools.SolrMarcIndexerException;
import org.solrmarc.tools.Utils;
import org.solrmarc.index.SolrIndexer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 *
 * @author Robert Haschart
 * @version $Id: VuFindIndexer.java 224 2008-11-05 19:33:21Z asnagy $
 *
 */
public class VuFindIndexer extends SolrIndexer
{
    // Initialize logging category
    static Logger logger = Logger.getLogger(VuFindIndexer.class.getName());

    // Initialize VuFind database connection (null until explicitly activated)
    private Connection vufindDatabase = null;
    private UpdateDateTracker tracker = null;

    // the SimpleDateFormat class is not Thread-safe the below line were changes to be not static 
    // which given the rest of the design of SolrMarc will make them work correctly.
    private SimpleDateFormat marc005date = new SimpleDateFormat("yyyyMMddHHmmss.S");
    private SimpleDateFormat marc008date = new SimpleDateFormat("yyMMdd");

    private static final Pattern COORDINATES_PATTERN = Pattern.compile("^([eEwWnNsS])(\\d{3})(\\d{2})(\\d{2})");
    private static final Pattern HDMSHDD_PATTERN = Pattern.compile("^([eEwWnNsS])(\\d+(\\.\\d+)?)");
    private static final Pattern PMDD_PATTERN = Pattern.compile("^([+-])(\\d+(\\.\\d+)?)");

    // Shutdown flag:
    private boolean shuttingDown = false;

    /**
     * Default constructor
     * @param propertiesMapFile the {@code x_index.properties} file mapping solr
     *  field names to values in the marc records
     * @param propertyDirs array of directories holding properties files
     * @throws Exception if {@code SolrIndexer} constructor threw an exception.
     */
    public VuFindIndexer(final String propertiesMapFile, final String[] propertyDirs)
            throws FileNotFoundException, IOException, ParseException {
        super(propertiesMapFile, propertyDirs);
    }

    /**
     * Log an error message and throw a fatal exception.
     * @param msg message to log
     */
    private void dieWithError(String msg)
    {
        logger.error(msg);
        throw new SolrMarcIndexerException(SolrMarcIndexerException.EXIT, msg);
    }

    /**
     * Connect to the VuFind database if we do not already have a connection.
     */
    private void connectToDatabase()
    {
        // Already connected?  Do nothing further!
        if (vufindDatabase != null) {
            return;
        }

        String dsn = ConfigManager.instance().getConfigSetting("config.ini", "Database", "database");

        try {
            // Parse key settings from the PHP-style DSN:
            String username = "";
            String password = "";
            String classname = "invalid";
            String prefix = "invalid";
            if (dsn.substring(0, 8).equals("mysql://")) {
                classname = "com.mysql.jdbc.Driver";
                prefix = "mysql";
            } else if (dsn.substring(0, 8).equals("pgsql://")) {
                classname = "org.postgresql.Driver";
                prefix = "postgresql";
            }

            Class.forName(classname).newInstance();
            String[] parts = dsn.split("://");
            if (parts.length > 1) {
                parts = parts[1].split("@");
                if (parts.length > 1) {
                    dsn = prefix + "://" + parts[1];
                    parts = parts[0].split(":");
                    username = parts[0];
                    if (parts.length > 1) {
                        password = parts[1];
                    }
                }
            }

            // Connect to the database:
            vufindDatabase = DriverManager.getConnection("jdbc:" + dsn, username, password);
        } catch (Throwable e) {
            dieWithError("Unable to connect to VuFind database");
        }

        Runtime.getRuntime().addShutdownHook(new VuFindShutdownThread(this));
    }

    private void disconnectFromDatabase()
    {
        if (vufindDatabase != null) {
            try {
                vufindDatabase.close();
            } catch (SQLException e) {
                System.err.println("Unable to disconnect from VuFind database");
                logger.error("Unable to disconnect from VuFind database");
            }
        }
    }

    public void shutdown()
    {
        disconnectFromDatabase();
        shuttingDown = true;
    }

    class VuFindShutdownThread extends Thread
    {
        private VuFindIndexer indexer;

        public VuFindShutdownThread(VuFindIndexer i)
        {
            indexer = i;
        }

        public void run()
        {
            indexer.shutdown();
        }
    }

    /**
     * Establish UpdateDateTracker object if not already available.
     */
    private void loadUpdateDateTracker() throws java.sql.SQLException
    {
        if (tracker == null) {
            connectToDatabase();
            tracker = new UpdateDateTracker(vufindDatabase);
        }
    }

    /**
     * Support method for getLatestTransaction.
     * @return Date extracted from 005 (or very old date, if unavailable)
     */
    private java.util.Date normalize005Date(String input)
    {
        // Normalize "null" strings to a generic bad value:
        if (input == null) {
            input = "null";
        }

        // Try to parse the date; default to "millisecond 0" (very old date) if we can't
        // parse the data successfully.
        java.util.Date retVal;
        try {
            retVal = marc005date.parse(input);
        } catch(java.text.ParseException e) {
            retVal = new java.util.Date(0);
        }
        return retVal;
    }

    /**
     * Support method for getLatestTransaction.
     * @return Date extracted from 008 (or very old date, if unavailable)
     */
    private java.util.Date normalize008Date(String input)
    {
        // Normalize "null" strings to a generic bad value:
        if (input == null || input.length() < 6) {
            input = "null";
        }

        // Try to parse the date; default to "millisecond 0" (very old date) if we can't
        // parse the data successfully.
        java.util.Date retVal;
        try {
            retVal = marc008date.parse(input.substring(0, 6));
        } catch(java.lang.StringIndexOutOfBoundsException e) {
            retVal = new java.util.Date(0);
        } catch(java.text.ParseException e) {
            retVal = new java.util.Date(0);
        }
        return retVal;
    }

    /**
     * Extract the latest transaction date from the MARC record.  This is useful
     * for detecting when a record has changed since the last time it was indexed.
     *
     * @param record MARC record
     * @return Latest transaction date.
     */
    public java.util.Date getLatestTransaction(Record record) {
        // First try the 005 -- this is most likely to have a precise transaction date:
        Set<String> dates = getFieldList(record, "005");
        if (dates != null) {
            Iterator<String> dateIter = dates.iterator();
            if (dateIter.hasNext()) {
                return normalize005Date(dateIter.next());
            }
        }

        // No luck with 005?  Try 008 next -- less precise, but better than nothing:
        dates = getFieldList(record, "008");
        if (dates != null) {
            Iterator<String> dateIter = dates.iterator();
            if (dateIter.hasNext()) {
                return normalize008Date(dateIter.next());
            }
        }

        // If we got this far, we couldn't find a valid value; return an arbitrary date:
        return new java.util.Date(0);
    }

    /**
     * Get all available publishers from the record.
     *
     * @param  record MARC record
     * @return set of publishers
     */
    public Set<String> getPublishers(final Record record) {
        Set<String> publishers = new LinkedHashSet<String>();

        // First check old-style 260b name:
        List<VariableField> list260 = record.getVariableFields("260");
        for (VariableField vf : list260)
        {
            DataField df = (DataField) vf;
            String currentString = "";
            for (Subfield current : df.getSubfields('b')) {
                currentString = currentString.trim().concat(" " + current.getData()).trim();
            }
            if (currentString.length() > 0) {
                publishers.add(currentString);
            }
        }

        // Now track down relevant RDA-style 264b names; we only care about
        // copyright and publication names (and ignore copyright names if
        // publication names are present).
        Set<String> pubNames = new LinkedHashSet<String>();
        Set<String> copyNames = new LinkedHashSet<String>();
        List<VariableField> list264 = record.getVariableFields("264");
        for (VariableField vf : list264)
        {
            DataField df = (DataField) vf;
            String currentString = "";
            for (Subfield current : df.getSubfields('b')) {
                currentString = currentString.trim().concat(" " + current.getData()).trim();
            }
            if (currentString.length() > 0) {
                char ind2 = df.getIndicator2();
                switch (ind2)
                {
                    case '1':
                        pubNames.add(currentString);
                        break;
                    case '4':
                        copyNames.add(currentString);
                        break;
                }
            }
        }
        if (pubNames.size() > 0) {
            publishers.addAll(pubNames);
        } else if (copyNames.size() > 0) {
            publishers.addAll(copyNames);
        }

        return publishers;
    }

    /**
     * Get all available dates from the record.
     *
     * @param  record MARC record
     * @return set of dates
     */
    public Set<String> getDates(final Record record) {
        Set<String> dates = new LinkedHashSet<String>();

        // First check old-style 260c date:
        List<VariableField> list260 = record.getVariableFields("260");
        for (VariableField vf : list260) {
            DataField df = (DataField) vf;
            List<Subfield> currentDates = df.getSubfields('c');
            for (Subfield sf : currentDates) {
                String currentDateStr = Utils.cleanDate(sf.getData());
                if (currentDateStr != null) dates.add(currentDateStr);
            }
        }

        // Now track down relevant RDA-style 264c dates; we only care about
        // copyright and publication dates (and ignore copyright dates if
        // publication dates are present).
        Set<String> pubDates = new LinkedHashSet<String>();
        Set<String> copyDates = new LinkedHashSet<String>();
        List<VariableField> list264 = record.getVariableFields("264");
        for (VariableField vf : list264) {
            DataField df = (DataField) vf;
            List<Subfield> currentDates = df.getSubfields('c');
            for (Subfield sf : currentDates) {
                String currentDateStr = Utils.cleanDate(sf.getData());
                char ind2 = df.getIndicator2();
                switch (ind2)
                {
                    case '1':
                        if (currentDateStr != null) pubDates.add(currentDateStr);
                        break;
                    case '4':
                        if (currentDateStr != null) copyDates.add(currentDateStr);
                        break;
                }
            }
        }
        if (pubDates.size() > 0) {
            dates.addAll(pubDates);
        } else if (copyDates.size() > 0) {
            dates.addAll(copyDates);
        }

        return dates;
    }

    /**
     * Get the earliest publication date from the record.
     *
     * @param  record MARC record
     * @return earliest date
     */
    public String getFirstDate(final Record record) {
        String result = null;
        Set<String> dates = getDates(record);
        for(String current: dates) {
            if (result == null || Integer.parseInt(current) < Integer.parseInt(result)) {
                result = current;
            }
        }
        return result;
    }

    /**
     * Get call numbers of a specific type.
     * 
     * <p>{@code fieldSpec} is of form {@literal 098abc:099ab}, does not accept subfield ranges.
     *
     *
     * @param record  current MARC record
     * @param fieldSpec  which MARC fields / subfields need to be analyzed
     * @param callTypeSf  subfield containing call number type, single character only
     * @param callType  literal call number code
     * @param result  a collection to gather the call numbers
     * @return collection of call numbers, same object as {@code result}
     */
    public static Collection<String> getCallNumberByTypeCollector(
            Record record, String fieldSpec, String callTypeSf, String callType, Collection<String> result) {
        for (String tag : fieldSpec.split(":")) {
            // Check to ensure tag length is at least 3 characters
            if (tag.length() < 3) {
                //TODO: Should this go to a log? Better message for a bad tag in a field spec?
                System.err.println("Invalid tag specified: " + tag);
                continue;
            }
            String dfTag = tag.substring(0, 3);
            String sfSpec = null;
            if (tag.length() > 3) {
                    sfSpec = tag.substring(3);
            }

            // do all fields for this tag
            for (VariableField vf : record.getVariableFields(dfTag)) {
                // Assume tag represents a DataField
                DataField df = (DataField) vf;
                boolean callTypeMatch = false;
                
                // Assume call type subfield could repeat
                for (Subfield typeSf : df.getSubfields(callTypeSf)) {
                    if (callTypeSf.indexOf(typeSf.getCode()) != -1 && typeSf.getData().equals(callType)) {
                        callTypeMatch = true;
                    }
                }
                System.err.println("callTypeMatch after loop: " + callTypeMatch);
                if (callTypeMatch) {
                    result.add(df.getSubfieldsAsString(sfSpec));
                }
            } // end loop over variable fields
        } // end loop over fieldSpec
        return result;
    }
    

    /**
     * Get call numbers of a specific type.
     * 
     * <p>{@code fieldSpec} is of form {@literal 098abc:099ab}, does not accept subfield ranges.
     *
     * @param record  current MARC record
     * @param fieldSpec  which MARC fields / subfields need to be analyzed
     * @param callTypeSf  subfield containing call number type, single character only
     * @param callType  literal call number code
     * @return set of call numbers
     */
    public static Set<String> getCallNumberByType(Record record, String fieldSpec, String callTypeSf, String callType) {
        return (Set<String>) getCallNumberByTypeCollector(record, fieldSpec, callTypeSf, callType,
                new LinkedHashSet<String>());
    }

    /**
     * Get call numbers of a specific type.
     * 
     * <p>{@code fieldSpec} is of form {@literal 098abc:099ab}, does not accept subfield ranges.
     *
     * @param record  current MARC record
     * @param fieldSpec  which MARC fields / subfields need to be analyzed
     * @param callTypeSf  subfield containing call number type, single character only
     * @param callType  literal call number code
     * @return list of call numbers
     */
    public static List<String> getCallNumberByTypeAsList(Record record, String fieldSpec, String callTypeSf, String callType) {
        return (List<String>) getCallNumberByTypeCollector(record, fieldSpec, callTypeSf, callType,
                new ArrayList<String>());
    }

    /**
     * Determine if a record is illustrated.
     *
     * @param  LC call number
     * @return "Illustrated" or "Not Illustrated"
     */
    public String isIllustrated(Record record) {
        String leader = record.getLeader().toString();

        // Does the leader indicate this is a "language material" that might have extra
        // illustration details in the fixed fields?
        if (leader.charAt(6) == 'a') {
            String currentCode = "";         // for use in loops below

            // List of 008/18-21 codes that indicate illustrations:
            String illusCodes = "abcdefghijklmop";

            // Check the illustration characters of the 008:
            ControlField fixedField = (ControlField) record.getVariableField("008");
            if (fixedField != null) {
                String fixedFieldText = fixedField.getData().toLowerCase();
                for (int i = 18; i <= 21; i++) {
                    if (i < fixedFieldText.length()) {
                        currentCode = fixedFieldText.substring(i, i + 1);
                        if (illusCodes.contains(currentCode)) {
                            return "Illustrated";
                        }
                    }
                }
            }

            // Now check if any 006 fields apply:
            List<VariableField> fields = record.getVariableFields("006");
            Iterator<VariableField> fieldsIter = fields.iterator();
            if (fields != null) {
                while(fieldsIter.hasNext()) {
                    fixedField = (ControlField) fieldsIter.next();
                    String fixedFieldText = fixedField.getData().toLowerCase();
                    for (int i = 1; i <= 4; i++) {
                         if (i < fixedFieldText.length()) {
                            currentCode = fixedFieldText.substring(i, i + 1);
                            if (illusCodes.contains(currentCode)) {
                                return "Illustrated";
                            }
                        }
                    }
                }
            }
        }

        // Now check for interesting strings in 300 subfield b:
        List<VariableField> fields = record.getVariableFields("300");
        Iterator<VariableField> fieldsIter = fields.iterator();
        if (fields != null) {
            DataField physical;
            while(fieldsIter.hasNext()) {
                physical = (DataField) fieldsIter.next();
                List<Subfield> subfields = physical.getSubfields('b');
                for (Subfield sf: subfields) {
                    String desc = sf.getData().toLowerCase();
                    if (desc.contains("ill.") || desc.contains("illus.")) {
                        return "Illustrated";
                    }
                }
            }
        }

        // If we made it this far, we found no sign of illustrations:
        return "Not Illustrated";
    }


    /**
     * Normalize LC numbers for sorting purposes (use only the first valid number!).
     * Will return first call number found if none pass validation,
     * or empty string if no call numbers.
     *
     * @param  record current MARC record
     * @param  fieldSpec which MARC fields / subfields need to be analyzed
     * @return sortable shelf key of the first valid LC number encountered, 
     *         otherwise shelf key of the first call number found.
     */
    public String getLCSortable(Record record, String fieldSpec) {
        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        String firstCall = "";
        for (String current : input) {
            // If this is a valid LC number, return the sortable shelf key:
            LCCallNumber callNum = new LCCallNumber(current);
            if (callNum.isValid()) {
                return callNum.getShelfKey();   // RETURN first valid
            }
            if (firstCall.length() == 0) {
                firstCall = current;
            }
        }

        // If we made it this far, did not find a valid LC number, so use what we have:
        return new LCCallNumber(firstCall).getShelfKey();
    }

    /**
     * Get sort key for first LC call number, identified by call type.
     * 
     * <p>{@code fieldSpec} is of form {@literal 098abc:099ab}, does not accept subfield ranges.
     *
     *
     * @param record  current MARC record
     * @param fieldSpec  which MARC fields / subfields need to be analyzed
     * @param callTypeSf  subfield containing call number type, single character only
     * @param callType  literal call number code
     * @return sort key for first identified LC call number
     */
    public String getLCSortableByType(
            Record record, String fieldSpec, String callTypeSf, String callType) {
        String sortKey = null;
        for (String tag : fieldSpec.split(":")) {
            // Check to ensure tag length is at least 3 characters
            if (tag.length() < 3) {
                //TODO: Should this go to a log? Better message for a bad tag in a field spec?
                System.err.println("Invalid tag specified: " + tag);
                continue;
            }
            String dfTag = tag.substring(0, 3);
            String sfSpec = null;
            if (tag.length() > 3) {
                    sfSpec = tag.substring(3);
            }

            // do all fields for this tag
            for (VariableField vf : record.getVariableFields(dfTag)) {
                // Assume tag represents a DataField
                DataField df = (DataField) vf;
                boolean callTypeMatch = false;
                
                // Assume call type subfield could repeat
                for (Subfield typeSf : df.getSubfields(callTypeSf)) {
                    if (callTypeSf.indexOf(typeSf.getCode()) != -1 && typeSf.getData().equals(callType)) {
                        callTypeMatch = true;
                    }
                }
                // take the first call number coded as LC
                if (callTypeMatch) {
                    sortKey = new LCCallNumber(df.getSubfieldsAsString(sfSpec)).getShelfKey();
                    break;
                }
            } // end loop over variable fields
        } // end loop over fieldSpec
        return sortKey;
    }

    /**
     * Extract a numeric portion of the Dewey decimal call number
     *
     * Can return null
     *
     * @param record current MARC record
     * @param fieldSpec which MARC fields / subfields need to be analyzed
     * @param precisionStr a decimal number (represented in string format) showing the
     *  desired precision of the returned number; i.e. 100 to round to nearest hundred,
     *  10 to round to nearest ten, 0.1 to round to nearest tenth, etc.
     * @return Set containing requested numeric portions of Dewey decimal call numbers
     */
    public Set<String> getDeweyNumber(Record record, String fieldSpec, String precisionStr) {
        // Initialize our return value:
        Set<String> result = new LinkedHashSet<String>();

        // Precision comes in as a string, but we need to convert it to a float:
        float precision = Float.parseFloat(precisionStr);

        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        for (String current: input) {
            DeweyCallNumber callNum = new DeweyCallNumber(current);
            if (callNum.isValid()) {
                // Convert the numeric portion of the call number into a float:
                float currentVal = Float.parseFloat(callNum.getClassification());
                
                // Round the call number value to the specified precision:
                Float finalVal = new Float(Math.floor(currentVal / precision) * precision);
                
                // Convert the rounded value back to a string (with leading zeros) and save it:
                // TODO: Provide different conversion to remove CallNumUtils dependency
                result.add(CallNumUtils.normalizeFloat(finalVal.toString(), 3, -1));
            }
        }

        // If we found no call number matches, return null; otherwise, return our results:
        if (result.isEmpty())
            return null;
        return result;
    }

    /**
     * Normalize Dewey numbers for searching purposes (uppercase/stripped spaces)
     *
     * Can return null
     *
     * @param record current MARC record
     * @param fieldSpec which MARC fields / subfields need to be analyzed
     * @return Set containing normalized Dewey numbers extracted from specified fields.
     */
    public Set<String> getDeweySearchable(Record record, String fieldSpec) {
        // Initialize our return value:
        Set<String> result = new LinkedHashSet<String>();

        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        Iterator<String> iter = input.iterator();
        while (iter.hasNext()) {
            // Get the current string to work on:
            String current = iter.next();

            // Add valid strings to the set, normalizing them to be all uppercase
            // and free from whitespace.
            DeweyCallNumber callNum = new DeweyCallNumber(current);
            if (callNum.isValid()) {
                result.add(callNum.toString().toUpperCase().replaceAll(" ", ""));
            }
        }

        // If we found no call numbers, return null; otherwise, return our results:
        if (result.isEmpty())
            return null;
        return result;
    }

    /**
     * Normalize Dewey numbers for sorting purposes (use only the first valid number!)
     *
     * Can return null
     *
     * @param record current MARC record
     * @param fieldSpec which MARC fields / subfields need to be analyzed
     * @return String containing the first valid Dewey number encountered, normalized
     *         for sorting purposes.
     */
    public String getDeweySortable(Record record, String fieldSpec) {
        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        Iterator<String> iter = input.iterator();
        while (iter.hasNext()) {
            // Get the current string to work on:
            String current = iter.next();

            // If this is a valid Dewey number, return the sortable shelf key:
            DeweyCallNumber callNum = new DeweyCallNumber(current);
            if (callNum.isValid()) {
                return callNum.getShelfKey();
            }
        }

        // If we made it this far, we didn't find a valid sortable Dewey number:
        return null;
    }

    /**
     * Get sort key for first Dewey call number, identified by call type.
     * 
     * <p>{@code fieldSpec} is of form {@literal 098abc:099ab}, does not accept subfield ranges.
     *
     *
     * @param record  current MARC record
     * @param fieldSpec  which MARC fields / subfields need to be analyzed
     * @param callTypeSf  subfield containing call number type, single character only
     * @param callType  literal call number code
     * @return sort key for first identified Dewey call number
     */
    public static String getDeweySortableByType(
            Record record, String fieldSpec, String callTypeSf, String callType) {
        String sortKey = null;
        for (String tag : fieldSpec.split(":")) {
            // Check to ensure tag length is at least 3 characters
            if (tag.length() < 3) {
                //TODO: Should this go to a log? Better message for a bad tag in a field spec?
                System.err.println("Invalid tag specified: " + tag);
                continue;
            }
            String dfTag = tag.substring(0, 3);
            String sfSpec = null;
            if (tag.length() > 3) {
                    sfSpec = tag.substring(3);
            }

            // do all fields for this tag
            for (VariableField vf : record.getVariableFields(dfTag)) {
                // Assume tag represents a DataField
                DataField df = (DataField) vf;
                boolean callTypeMatch = false;
                
                // Assume call type subfield could repeat
                for (Subfield typeSf : df.getSubfields(callTypeSf)) {
                    if (callTypeSf.indexOf(typeSf.getCode()) != -1 && typeSf.getData().equals(callType)) {
                        callTypeMatch = true;
                    }
                }
                // take the first call number coded as Dewey
                if (callTypeMatch) {
                    sortKey = new DeweyCallNumber(df.getSubfieldsAsString(sfSpec)).getShelfKey();
                    break;
                }
            } // end loop over variable fields
        } // end loop over fieldSpec
        return sortKey;
    }

    
    /**
     * Normalize Dewey numbers for AlphaBrowse sorting purposes (use all numbers!)
     *
     * Can return null
     *
     * @param record current MARC record
     * @param fieldSpec which MARC fields / subfields need to be analyzed
     * @return List containing normalized Dewey numbers extracted from specified fields.
     */
    public List<String> getDeweySortables(Record record, String fieldSpec) {
        // Initialize our return value:
        List<String> result = new LinkedList<String>();

        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        Iterator<String> iter = input.iterator();
        while (iter.hasNext()) {
            // Get the current string to work on:
            String current = iter.next();

            // gather all sort keys, even if number is not valid
            DeweyCallNumber callNum = new DeweyCallNumber(current);
            result.add(callNum.getShelfKey());
        }

        // If we found no call numbers, return null; otherwise, return our results:
        if (result.isEmpty())
            return null;
        return result;
    }

    /**
     * The following several methods are designed to get latitude and longitude
     * coordinates.
     * Records can have multiple coordinates sets of points and/or rectangles.
     * Points are represented by coordinate sets where N=S E=W.
     *
     * code adapted from xrosecky - Moravian Library
     * https://github.com/moravianlibrary/VuFind-2.x/blob/master/import/index_scripts/geo.bsh
     * and incorporates VuFind location.bsh functionality for GoogleMap display.
     */

    /**
     * Convert MARC coordinates into location_geo format.
     *
     * @param  Record record
     * @return List   geo_coordinates
     */
    public List<String> getAllCoordinates(Record record) {
        List<String> geo_coordinates = new ArrayList<String>();
        List<VariableField> list034 = record.getVariableFields("034");
        if (list034 != null) {
            for (VariableField vf : list034) {
                DataField df = (DataField) vf;
                String d = df.getSubfield('d').getData();
                String e = df.getSubfield('e').getData();
                String f = df.getSubfield('f').getData();
                String g = df.getSubfield('g').getData();
                //System.out.println("raw Coords: "+d+" "+e+" "+f+" "+g);

                // Check to see if there are only 2 coordinates
                // If so, copy them into the corresponding coordinate fields
                if ((d !=null && (e == null || e.trim().equals(""))) && (f != null && (g==null || g.trim().equals("")))) {
                    e = d;
                    g = f;
                }
                if ((e !=null && (d == null || d.trim().equals(""))) && (g != null && (f==null || f.trim().equals("")))) {
                    d = e;
                    f = g;
                }

                // Check and convert coordinates to +/- decimal degrees
                Double west = convertCoordinate(d);
                Double east = convertCoordinate(e);
                Double north = convertCoordinate(f);
                Double south = convertCoordinate(g);

                // New Format for indexing coordinates in Solr 5.0 - minX, maxX, maxY, minY
                // Note - storage in Solr follows the WENS order, but display is WSEN order
                String result = String.format("ENVELOPE(%s,%s,%s,%s)", new Object[] { west, east, north, south });

                if (validateCoordinates(west, east, north, south)) {
                    geo_coordinates.add(result);
                }
            }
        }
        return geo_coordinates;
    }

    /**
     * Get point coordinates for GoogleMap display.
     *
     * @param  Record record
     * @return List   coordinates
     */
    public List<String> getPointCoordinates(Record record) {
        List<String> coordinates = new ArrayList<String>();
        List<VariableField> list034 = record.getVariableFields("034");
        if (list034 != null) {
            for (VariableField vf : list034) {
                DataField df = (DataField) vf;
                String d = df.getSubfield('d').getData();
                String e = df.getSubfield('e').getData();
                String f = df.getSubfield('f').getData();
                String g = df.getSubfield('g').getData();

                // Check to see if there are only 2 coordinates
                if ((d !=null && (e == null || e.trim().equals(""))) && (f != null && (g==null || g.trim().equals("")))) {
                    Double long_val = convertCoordinate(d);
                    Double lat_val = convertCoordinate(f);
                    String longlatCoordinate = Double.toString(long_val) + ',' + Double.toString(lat_val);
                    coordinates.add(longlatCoordinate);
                }
                if ((e !=null && (d == null || d.trim().equals(""))) && (g != null && (f==null || f.trim().equals("")))) {
                    Double long_val = convertCoordinate(e);
                    Double lat_val = convertCoordinate(g);
                    String longlatCoordinate = Double.toString(long_val) + ',' + Double.toString(lat_val);
                    coordinates.add(longlatCoordinate);
                }
                // Check if N=S and E=W
                if (d.equals(e) && f.equals(g)) {
                    Double long_val = convertCoordinate(d);
                    Double lat_val = convertCoordinate(f);
                    String longlatCoordinate = Double.toString(long_val) + ',' + Double.toString(lat_val);
                    coordinates.add(longlatCoordinate);
                }
            }
        }
        return coordinates;
    }

    /**
     * Get all available coordinates from the record.
     *
     * @param  Record record
     * @return List   geo_coordinates
     */
    public List<String> getDisplayCoordinates(Record record) {
        List<String> geo_coordinates = new ArrayList<String>();
        List<VariableField> list034 = record.getVariableFields("034");
        if (list034 != null) {
            for (VariableField vf : list034) {
                DataField df = (DataField) vf;
                String west = df.getSubfield('d').getData();
                String east = df.getSubfield('e').getData();
                String north = df.getSubfield('f').getData();
                String south = df.getSubfield('g').getData();
                String result = String.format("%s %s %s %s", new Object[] { west, east, north, south });
                if (west != null || east != null || north != null || south != null) {
                    geo_coordinates.add(result);
                }
            }
        }
        return geo_coordinates;
    }

    /**
     * Check coordinate type HDMS HDD or +/-DD.
     *
     * @param  String coordinateStr
     * @return Double coordinate
     */
    protected Double convertCoordinate(String coordinateStr) {
        Double coordinate = Double.NaN;
        Matcher HDmatcher = HDMSHDD_PATTERN.matcher(coordinateStr);
        Matcher PMDmatcher = PMDD_PATTERN.matcher(coordinateStr);
        if (HDmatcher.matches()) {
            String hemisphere = HDmatcher.group(1).toUpperCase();
            Double degrees = Double.parseDouble(HDmatcher.group(2));
            // Check for HDD or HDMS
            if (hemisphere.equals("N") || hemisphere.equals("S")) {
                if (degrees > 90) {
                    String hdmsCoordinate = hemisphere+"0"+HDmatcher.group(2);
                    coordinate = coordinateToDecimal(hdmsCoordinate);
                } else {
                    coordinate = Double.parseDouble(HDmatcher.group(2));
                    if (hemisphere.equals("S")) {
                        coordinate *= -1;
                    }
                }
            }
            if (hemisphere.equals("E") || hemisphere.equals("W")) {
                if (degrees > 180) {
                    String hdmsCoordinate = HDmatcher.group(0);
                    coordinate = coordinateToDecimal(hdmsCoordinate);
                } else {
                    coordinate = Double.parseDouble(HDmatcher.group(2));
                    if (hemisphere.equals("W")) {
                        coordinate *= -1;
                    }
                }
            }
            return coordinate;
        } else if (PMDmatcher.matches()) {
            String hemisphere = PMDmatcher.group(1);
            coordinate = Double.parseDouble(PMDmatcher.group(2));
            if (hemisphere.equals("-")) {
                coordinate *= -1;
            }
            return coordinate;
        } else {
            return null;
        }
    }

    /**
     * Convert HDMS coordinates to decimal degrees.
     *
     * @param  String coordinateStr
     * @return Double coordinate
     */
    protected Double coordinateToDecimal(String coordinateStr) {
        Matcher matcher = COORDINATES_PATTERN.matcher(coordinateStr);
        if (matcher.matches()) {
            String hemisphere = matcher.group(1).toUpperCase();
            int degrees = Integer.parseInt(matcher.group(2));
            int minutes = Integer.parseInt(matcher.group(3));
            int seconds = Integer.parseInt(matcher.group(4));
            double coordinate = degrees + (minutes / 60.0) + (seconds / 3600.0);
            if (hemisphere.equals("W") || hemisphere.equals("S")) {
                coordinate *= -1;
            }
            return coordinate;
        }
        return null;
    }

    /**
     * Check decimal degree coordinates to make sure they are valid.
     *
     * @param  Double west, east, north, south
     * @return boolean
     */
    protected boolean validateCoordinates(Double west, Double east, Double north, Double south) {
        if (west == null || east == null || north == null || south == null) {
            return false;
        }
        if (west > 180.0 || west < -180.0 || east > 180.0 || east < -180.0) {
            return false;
        }
        if (north > 90.0 || north < -90.0 || south > 90.0 || south < -90.0) {
            return false;
        }
        if (north < south || west > east) {
            return false;
        }
        return true;
    }

    /**
     * THIS FUNCTION HAS BEEN DEPRECATED.
     * Determine the longitude and latitude of the items location.
     *
     * @param  record current MARC record
     * @return string of form "longitude, latitude"
     */
    public String getLongLat(Record record) {
        // Check 034 subfield d and f
        List<VariableField> fields = record.getVariableFields("034");
        Iterator<VariableField> fieldsIter = fields.iterator();
        if (fields != null) {
            DataField physical;
            while(fieldsIter.hasNext()) {
                physical = (DataField) fieldsIter.next();
                String val = null;

                List<Subfield> subfields_d = physical.getSubfields('d');
                Iterator<Subfield> subfieldsIter_d = subfields_d.iterator();
                if (subfields_d != null) {
                    while (subfieldsIter_d.hasNext()) {
                        val = subfieldsIter_d.next().getData().trim();
                        if (!val.matches("-?\\d+(.\\d+)?")) {
                            return null;
                        }
                    }
                }
                List<Subfield> subfields_f = physical.getSubfields('f');
                Iterator<Subfield> subfieldsIter_f = subfields_f.iterator();
                if (subfields_f != null) {
                    while (subfieldsIter_f.hasNext()) {
                        String val2 = subfieldsIter_f.next().getData().trim();
                        if (!val2.matches("-?\\d+(.\\d+)?")) {
                            return null;
                        }
                        val = val + ',' + val2;
                    }
                }
                return val;
            }
        }
        //otherwise return null
        return null;
    }

    /**
     * Update the index date in the database for the specified core/ID pair.  We
     * maintain a database of "first/last indexed" times separately from Solr to
     * allow the history of our indexing activity to be stored permanently in a
     * fashion that can survive even a total Solr rebuild.
     */
    public UpdateDateTracker updateTracker(String core, String id, java.util.Date latestTransaction)
    {
        // Update the database (if necessary):
        try {
            // Initialize date tracker if not already initialized:
            loadUpdateDateTracker();

            tracker.index(core, id, latestTransaction);
        } catch (java.sql.SQLException e) {
            // If we're in the process of shutting down, an error is expected:
            if (!shuttingDown) {
                dieWithError("Unexpected database error");
            }
        }

        // Send back the tracker object so the caller can use it (helpful for
        // use in BeanShell scripts).
        return tracker;
    }

    /**
     * Get the "first indexed" date for the current record.  (This is the first
     * time that SolrMarc ever encountered this particular record).
     *
     * @param record current MARC record
     * @param fieldSpec fields / subfields to be analyzed
     * @param core core name
     * @return ID string
     */
    public String getFirstIndexed(Record record, String fieldSpec, String core) {
        // Update the database, then send back the first indexed date:
        updateTracker(core, getFirstFieldVal(record, fieldSpec), getLatestTransaction(record));
        return tracker.getFirstIndexed();
    }

    /**
     * Get the "first indexed" date for the current record.  (This is the first
     * time that SolrMarc ever encountered this particular record).
     *
     * @param record current MARC record
     * @param fieldSpec fields / subfields to be analyzed
     * @return ID string
     */
    public String getFirstIndexed(Record record, String fieldSpec) {
        return getFirstIndexed(record, fieldSpec, "biblio");
    }

    /**
     * Get the "first indexed" date for the current record.  (This is the first
     * time that SolrMarc ever encountered this particular record).
     *
     * @param record current MARC record
     * @return ID string
     */
    public String getFirstIndexed(Record record) {
        return getFirstIndexed(record, "001", "biblio");
    }

    /**
     * Get the "last indexed" date for the current record.  (This is the last time
     * the record changed from SolrMarc's perspective).
     *
     * @param record current MARC record
     * @param fieldSpec fields / subfields to be analyzed
     * @param core core name
     * @return ID string
     */
    public String getLastIndexed(Record record, String fieldSpec, String core) {
        // Update the database, then send back the last indexed date:
        updateTracker(core, getFirstFieldVal(record, fieldSpec), getLatestTransaction(record));
        return tracker.getLastIndexed();
    }

    /**
     * Get the "last indexed" date for the current record.  (This is the last time
     * the record changed from SolrMarc's perspective).
     *
     * @param record current MARC record
     * @param fieldSpec fields / subfields to analyze
     * @return ID string
     */
    public String getLastIndexed(Record record, String fieldSpec) {
        return getLastIndexed(record, fieldSpec, "biblio");
    }

    /**
     * Get the "last indexed" date for the current record.  (This is the last time
     * the record changed from SolrMarc's perspective).
     *
     * @param record current MARC record
     * @return ID string
     */
    public String getLastIndexed(Record record) {
        return getLastIndexed(record, "001", "biblio");
    }

    /**
     * Load configurations for the full text parser.  Return an array containing the
     * parser type in the first element and the parser configuration in the second
     * element.
     *
     * @return String[]
     */
    public String[] getFulltextParserSettings()
    {
        String parserType = ConfigManager.instance().getConfigSetting(
            "fulltext.ini", "General", "parser"
        );
        if (null != parserType) {
            parserType = parserType.toLowerCase();
        }

        // Is Aperture active?
        String aperturePath = ConfigManager.instance().getConfigSetting(
            "fulltext.ini", "Aperture", "webcrawler"
        );
        if ((null == parserType && null != aperturePath)
            || (null != parserType && parserType.equals("aperture"))
        ) {
            String[] array = { "aperture", aperturePath };
            return array;
        }

        // Is Tika active?
        String tikaPath = ConfigManager.instance().getConfigSetting(
            "fulltext.ini", "Tika", "path"
        );
        if ((null == parserType && null != tikaPath)
            || (null != parserType && parserType.equals("tika"))
        ) {
            String[] array = { "tika", tikaPath };
            return array;
        }

        // No recognized parser found:
        String[] array = { "none", null };
        return array;
    }

    /**
     * Extract full-text from the documents referenced in the tags
     *
     * @param Record record current MARC record
     * @param String field spec to search for URLs
     * @param String only harvest files matching this extension (null for all)
     * @return String The full-text
     */
    public String getFulltext(Record record, String fieldSpec, String extension) {
        String result = "";

        // Get the web crawler settings (and return no text if it is unavailable)
        String[] parserSettings = getFulltextParserSettings();
        if (parserSettings[0].equals("none")) {
            return null;
        }

        // Loop through the specified MARC fields:
        Set<String> fields = getFieldList(record, fieldSpec);
        Iterator<String> fieldsIter = fields.iterator();
        if (fields != null) {
            while(fieldsIter.hasNext()) {
                // Get the current string to work on (and sanitize spaces):
                String current = fieldsIter.next().replaceAll(" ", "%20");
                // Filter by file extension
                if (extension == null || current.endsWith(extension)) {
                    // Load the parser output for each tag into a string
                    result = result + harvestWithParser(current, parserSettings);
                }
            }
        }
        // return string to SolrMarc
        return result;
    }

    /**
     * Extract full-text from the documents referenced in the tags
     *
     * @param Record record current MARC record
     * @param String field spec to search for URLs
     * @return String The full-text
     */
    public String getFulltext(Record record, String fieldSpec) {
        return getFulltext(record, fieldSpec, null);
    }

    /**
     * Extract full-text from the documents referenced in the tags
     *
     * @param Record record current MARC record
     * @return String The full-text
     */
    public String getFulltext(Record record) {
        return getFulltext(record, "856u", null);
    }

    /**
     * Clean up XML data generated by Aperture
     *
     * @param f file to clean
     * @return a fixed version of the file
     */
    public File sanitizeApertureOutput(File f) throws IOException
    {
        //clean up the aperture xml output
        File tempFile = File.createTempFile("buffer", ".tmp");
        FileOutputStream fw = new FileOutputStream(tempFile);
        OutputStreamWriter writer = new OutputStreamWriter(fw, "UTF8");

        //delete this control character from the File and save
        FileReader fr = new FileReader(f);
        BufferedReader br = new BufferedReader(fr);
        while (br.ready()) {
            writer.write(sanitizeFullText(br.readLine()));
        }
        writer.close();
        br.close();
        fr.close();

        return tempFile;
    }

    /**
     * Clean up bad characters in the full text.
     *
     * @param text text to clean
     * @return cleaned text
     */
    public String sanitizeFullText(String text)
    {
        String badChars = "[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD\\u10000-\\u10FFFF]+";
        return text.replaceAll(badChars, " ");
    }

    /**
     * Harvest the contents of a document file (PDF, Word, etc.) using Aperture.
     * This method will only work if Aperture is properly configured in the
     * fulltext.ini file.  Without proper configuration, this will simply return an
     * empty string.
     *
     * @param url the url extracted from the MARC tag.
     * @param aperturePath The path to Aperture
     * @return full-text extracted from url
     */
    public String harvestWithAperture(String url, String aperturePath) {
        String plainText = "";
        // Create temp file.
        File f = null;
        try {
            f = File.createTempFile("apt", ".txt");
        } catch (Throwable e) {
            dieWithError("Unable to create temporary file for full text harvest.");
        }

        // Delete temp file when program exits.
        f.deleteOnExit();

        // Construct the command to call Aperture
        String cmd = aperturePath + " -o " + f.getAbsolutePath().toString()  + " -x " + url;

        // Call Aperture
        //System.out.println("Loading fulltext from " + url + ". Please wait ...");
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            
            // Debugging output
            /*
            BufferedReader stdInput = new BufferedReader(new
                InputStreamReader(p.getInputStream()));
            String s;
            while ((s = stdInput.readLine()) != null) {
                System.out.println(s);
            }
            */
            
            // Wait for Aperture to finish
            p.waitFor();
        } catch (Throwable e) {
            logger.error("Problem executing Aperture -- " + e.getMessage());
        }

        // Parse Aperture XML output
        Document xmlDoc = null;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            File tempFile = sanitizeApertureOutput(f);
            xmlDoc = db.parse(tempFile);
            NodeList nl = xmlDoc.getElementsByTagName("plainTextContent");
            if(nl != null && nl.getLength() > 0) {
                Node node = nl.item(0);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    plainText = plainText + node.getTextContent();
                }
            }

            // we'll hold onto the temp file if it failed to parse for debugging;
            // only set it up to be deleted if we've made it this far successfully.
            tempFile.deleteOnExit();
        } catch (Throwable e) {
            logger.error("Problem parsing Aperture XML -- " + e.getMessage());
        }

        return plainText;
    }

    /**
     * Harvest the contents of a document file (PDF, Word, etc.) using Tika.
     * This method will only work if Tika is properly configured in the fulltext.ini
     * file.  Without proper configuration, this will simply return an empty string.
     *
     * @param url the url extracted from the MARC tag.
     * @param scraperPath path to Tika
     * @return the full-text
     */
    public String harvestWithTika(String url, String scraperPath) {

        // Construct the command
        String cmd = "java -jar " + scraperPath + " -t -eUTF8 " + url;

        StringBuilder stringBuilder= new StringBuilder();

        // Call our scraper
        //System.out.println("Loading fulltext from " + url + ". Please wait ...");
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader stdInput = new BufferedReader(new
                InputStreamReader(p.getInputStream(), "UTF8"));

            // We'll build the string from the command output
            String s;
            while ((s = stdInput.readLine()) != null) {
                stringBuilder.append(s);
            }
        } catch (Throwable e) {
            logger.error("Problem with Tika -- " + e.getMessage());
        }

        return sanitizeFullText(stringBuilder.toString());
    }

    /**
     * Harvest the contents of a document file (PDF, Word, etc.) using the active parser.
     *
     * @param url the URL extracted from the MARC tag.
     * @param settings configuration settings from {@code getFulltextParserSettings}.
     * @return the full-text
     */
    public String harvestWithParser(String url, String[] settings) {
        if (settings[0].equals("aperture")) {
            return harvestWithAperture(url, settings[1]);
        } else if (settings[0].equals("tika")) {
            return harvestWithTika(url, settings[1]);
        }
        return null;
    }

    /**
     * Get access to the Logger object.
     *
     * @return Logger
     */
    public Logger getLogger()
    {
        return logger;
    }

    /**
     * Normalize trailing punctuation. This mimics the functionality built into VuFind's
     * textFacet field type, so that you can get equivalent values when indexing into
     * a string field. (Useful for docValues support).
     *
     * Can return null
     *
     * @param record current MARC record
     * @param fieldSpec which MARC fields / subfields need to be analyzed
     * @return Set containing normalized values
     */
    public Set<String> normalizeTrailingPunctuation(Record record, String fieldSpec) {
        // Initialize our return value:
        Set<String> result = new LinkedHashSet<String>();

        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        Pattern pattern = Pattern.compile("(?<!\b[A-Z])[.\\s]*$");
        for (String current: input) {
            result.add(pattern.matcher(current).replaceAll(""));
        }

        // If we found no matches, return null; otherwise, return our results:
        return result.isEmpty() ? null : result;
    }
}