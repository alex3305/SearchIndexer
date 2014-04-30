import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;

import org.apache.commons.io.FilenameUtils;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;

/**
 * Search Indexer based on Solr and SolrJ. This application will index all
 * the paths that are configured and send the found files to the configured solrURL.
 *
 * Note: Edit the config.properties file to add your own parameters. The defaults will
 * be filled in, but you must add your own paths and solrURL!
 *
 * @author alexh
 */
public class App {

    /** Delimiter that is used to serialize a regular string to a Java array. */
    static final String DELIMITER_SEPERATOR = "\\|";

    /** Delimiter that is used to find paths to replace. */
    static final String DELIMITER_REPLACE = "\\*";

    /**
     * Default Content-Type to send to Solr. Could change when the Solr specs change.
     * This value will be overriden by the set FILE_CONFIG.
     */
    private static final String DEFAULT_CONTENTTYPE = "application/octet-stream";

    /**
     * Default value to determine whether debug mode is enabled.
     * This value will be overridden by the set FILE_CONFIG.
     */
    private static final boolean DEFAULT_DEBUG = false;

    /**
     * Default extensions that are indexed. These include HTML, XML, MsOffice, PDF,
     * TXT, OpenDocument format, RTF and java source files.
     * This value will be overriden by the set FILE_CONFIG.
     */
    private static final String[] DEFAULT_EXTENSIONS = {"htm", "html", "xml", "doc", "docx", "xls", "xlsx", "ppt",
                                                        "pptx", "pdf", "txt", "odm", "odp", "ods", "odt", "rtf",
                                                        "rtf", "java"};

    /**
     * Default maximum file size that will be send to the Solr instance. Default is 16MB.
     * This value will be overriden by the set FILE_CONFIG.
     */
    private static final long DEFAULT_MAXFILESIZE = 16777216;

    /** Error message when the configuration file couldn't be read. */
    private static final String ERR_CONFIG = "Error: Couldn't read config.properties. (%s)";

    /** Error message when there was an IOException while writing to the delta file. */
    private static final String ERR_DELTA_APPEND = "Error: Couldn't write to delta file (%s).";

    /** Error message when the a delta file couldn't be created. */
    private static final String ERR_DELTA_CREATE = "Error: Couldn't create delta file (%s).";

    /** Error message when a fatal error occurs when a property is missing. */
    private static final String ERR_FATAL = "This value is mandatory for this application.";

    /** Error message for generic errors. */
    private static final String ERR_GENERIC = "Error: %s";

    /** Error message when last modified time couldn't be found... */
    private static final String ERR_LAST_MDO = "Couldn't get last modified time of %s.";

    /** Error message when no paths are found to crawl in the configuration. */
    private static final String ERR_NO_PATHS = "Error: No paths found in %s";

    /** Error message when the Solr URI cannot be found in the configuration. */
    private static final String ERR_NO_SOLRURI = "No Solr URI found.";

    /** Error message when a proprty is missing. */
    private static final String ERR_PROP_MISSING = "Property %s wasn't found in %s. %s";

    /** Static configuration file. Edit to your preferences there. */
    private static final String FILE_CONFIG = "config.properties";

    /**
     * File list with last indexed files and last modified time so only
     * new or changed files will be updated.
     */
    private static final String FILE_DELTA = "files.delta";

    /** Out message when a default property is used. */
    private static final String OUT_DEFAULT = "Default property will be used.";

    /** Out message when something is done. */
    private static final String OUT_DONE = " Done.";

    /** Out message when the indexing is complete. */
    private static final String OUT_FINAL_DONE = "Done indexing the given paths!";

    /** Out message when pushing out a file to Solr. */
    private static final String OUT_NOT_PUSHING = "Not pushing: %s";

    /** Out message when pushing out a file to Solr. */
    private static final String OUT_PUSHING = "Pushing: %s ...";

    /** Out message when replacing a path with another. */
    private static final String OUT_REPLACE = "Replacing path %s with %s.";

    /** Out message when starting the crawl. */
    private static final String OUT_START = "Starting index of: %s";

    /** Set Content-Type that is used to post a file to Solr. */
    private String contentType;

    /** Set value whether debug is enabled. This will be extra verbose. */
    private boolean debug;

    /** List with all the delta files that are known to us */
    private List<DeltaFile> deltaFileList;

    /** Set file extensions that will be crawled */
    private String[] extensions;

    /** Set maximum file size of the files that will be send to Solr. */
    private long maxFileSize;

    /** Set paths that will be crawled. */
    private String[] paths;

    /** Paths that need to be replaced, i.e. for Samba crawling. */
    private HashMap<String, String> pathsToReplace;

    /** Set SolrURI that is used to connect to the Solr core. */
    private String solrURI;

    /**
     * Constructs a new App with default values set to the variables. When
     * the properties file is loaded these values should be overridden.
     */
    private App() {
        this.contentType = App.DEFAULT_CONTENTTYPE;
        this.debug = App.DEFAULT_DEBUG;
        this.extensions = App.DEFAULT_EXTENSIONS;
        this.maxFileSize = App.DEFAULT_MAXFILESIZE;
        this.deltaFileList = new ArrayList<>();
    }

    /**
     * Replaces paths or part of paths with new values provided in the
     * configuration file. This can be a useful function when used in
     * conjunction with Samba shares which are locally mounted.
     *
     * If there are no paths given to replace, this will always return
     * the original path. Otherwise it will try to replace the original
     * with the given substitute. If there is no substitute found, again
     * the original path will be returned.
     *
     * @param path Path to replace.
     * @return Either the original path as a String or the (partially)
     *         replaced path.
     */
    private String replacePathFromMap(Path path) {
        String p = path.toString();

        if (this.pathsToReplace.isEmpty())
            return p;

        for (Map.Entry<String, String> entry : this.pathsToReplace.entrySet()) {
            if (p.contains(entry.getKey())) {
                return p.replace(entry.getKey(), entry.getValue());
            }
        }

        return p;
    }

    /**
     * The actual main method that is also in an instance of this App. This
     * method accepts the properties from the properties file and will start
     * the crawl. This method will crash the application when the Solr URI that
     * is provided is invalid.
     *
     * @param properties Properties (file) to load into this application.
     */
    private void doMain(Properties properties) {
        this.loadProperties(properties);

        // Check for URI. When the URI is malformed, crash the application with a message.
        try {
            new URI(this.solrURI);
        } catch (URISyntaxException e) {
            System.err.print(String.format(App.ERR_GENERIC, e.getMessage()));
            System.exit(0);
        }

        for (String str : this.paths) {
            System.out.println();
            System.out.println(String.format(App.OUT_START, str));
            this.readDeltaFile();
            this.indexPaths(Paths.get(str));
        }

        System.out.println();
        System.out.println(App.OUT_FINAL_DONE);
    }

    /**
     * Indexes the given paths that are loaded from the configuration file
     * and will crawl it. This method will also check whether a file is
     * readable, if the size is below the maximum size and if the extension
     * is allowed to be send to the server.
     *
     * If a file isn't readable, larger than the maximum given file size or
     * when the extension isn't allowed to be send to Solr, the iteration
     * will be skipped.
     *
     * This method is also recursive and will call itself when it finds a
     * child directory.
     *
     * @param path Path to parse.
     */
    private void indexPaths(Path path) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
            for (Path p : ds) {
                if (!Files.isReadable(p)) {
                    continue;
                }
                if (Files.size(p) > this.maxFileSize) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    this.indexPaths(p);
                } else {
                    for (String ext : this.extensions) {
                        if (ext.equals(FilenameUtils.getExtension(p.toString()).toLowerCase())) {
                            if (!this.isFileModified(p)) {
                                this.sendToSolr(p);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.print(String.format(App.ERR_GENERIC, e.getMessage()));
        }
    }

    /**
     * Checks whether the provided absolute path has been modified, comparing it
     * to paths in the delta file that is already known to us.
     *
     * @param p Path to check whether it's modified
     * @return Value to determine whether a path has been modified.
     */
    private boolean isFileModified(Path p) {
        boolean isModified = false;

        for (DeltaFile delta : this.deltaFileList) {
            if (p.equals(delta.getPath())) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() == delta.getLastModified().toMillis()) {
                        if (this.debug) {
                            System.out.println(String.format(App.OUT_NOT_PUSHING, p.toAbsolutePath()));
                        }

                        isModified = true;
                    }
                } catch (IOException e) {
                    if (this.debug) {
                        System.err.println(String.format(App.ERR_LAST_MDO, p.toAbsolutePath()));
                    }
                }
            }
        }

        try {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(App.FILE_DELTA, true))) {
                writer.append(String.format("%s%s%s",
                        p.toAbsolutePath(), App.DELIMITER_SEPERATOR, Files.getLastModifiedTime(p).toMillis()));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println(String.format(App.ERR_DELTA_APPEND, App.FILE_DELTA));
        }

        return isModified;
    }

    /**
     * Loads the configuration properties from the given configuration
     * file. This method will try to look up all the values it will be
     * expecting in the configuration file. When a value cannot be found
     * it will print an error regarding what property is missing.
     *
     * Most of the properties are optional and the defaults will be used,
     * but this application will not work when 'paths' or 'solrURI' isn't
     * found. That's why it will crash with the appropiate error message.
     *
     * @param properties Properties (file) to load into this application.
     */
    private void loadProperties(Properties properties) {
        try {
            this.contentType = properties.getProperty("contentType");
        } catch (NullPointerException e) {
            System.err.println(String.format(App.ERR_PROP_MISSING, "contentType", App.FILE_CONFIG, App.OUT_DEFAULT));
        }

        try {
            this.debug = Boolean.parseBoolean(properties.getProperty("debug"));
        } catch (NullPointerException e) {
            System.err.println(String.format(App.ERR_PROP_MISSING, "debug", App.FILE_CONFIG, App.OUT_DEFAULT));
        }

        try {
            this.extensions = properties.getProperty("extensions").split(App.DELIMITER_SEPERATOR);
        } catch (NullPointerException e) {
            System.err.println(String.format(App.ERR_PROP_MISSING, "extensions", App.FILE_CONFIG, App.OUT_DEFAULT));
        }

        try {
            this.maxFileSize = Long.parseLong(properties.getProperty("maxFileSize"));
        } catch (NullPointerException e) {
            System.err.println(String.format(App.ERR_PROP_MISSING, "maxFileSize", App.FILE_CONFIG, App.OUT_DEFAULT));
        }

        try {
            this.pathsToReplace = new HashMap<>();
            for (String path : properties.getProperty("pathsToReplace").split(App.DELIMITER_REPLACE)) {
                String[] rp = path.split(App.DELIMITER_SEPERATOR);
                this.pathsToReplace.put(rp[0], rp[1]);
                if (this.debug) {
                    System.out.println(String.format(App.OUT_REPLACE, rp[0], rp[1]));
                }
            }
        } catch (NullPointerException e) {
            System.err.println(String.format(App.ERR_PROP_MISSING, "pathsToReplace", App.FILE_CONFIG, App.OUT_DEFAULT));
        }

        try {
            this.paths = properties.getProperty("paths").split(App.DELIMITER_SEPERATOR);
        } catch (NullPointerException e) {
            System.err.println(String.format(App.ERR_PROP_MISSING, "paths", App.FILE_CONFIG, App.ERR_FATAL));
            System.exit(0);
        }

        try {
            this.solrURI = properties.getProperty("solrURI");
        } catch (NullPointerException e) {
            System.err.println(String.format(App.ERR_PROP_MISSING, "solrURI", App.FILE_CONFIG, App.ERR_FATAL));
            System.exit(0);
        }

        if (this.paths.length == 0) {
            System.err.println(String.format(App.ERR_NO_PATHS, App.FILE_CONFIG));
            System.exit(0);
        }

        if (this.solrURI.equals("")) {
            System.err.println(App.ERR_NO_SOLRURI);
            System.exit(0);
        }
    }

    /**
     * Reads the delta file and creates new objects in memory, which is
     * faster when parsing paths. This method will append all the found
     * files to a list.
     */
    private void readDeltaFile() {
        Path deltaFile = Paths.get(App.FILE_DELTA);
        try (BufferedReader reader = Files.newBufferedReader(deltaFile, Charset.forName("UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                this.deltaFileList.add(new DeltaFile(line));
            }

            // Read whole file? flush it! Otherwise our delta file would grow massively.
            try {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(App.FILE_DELTA, false))) {
                    writer.write("");
                }
            } catch (IOException e) {
                System.err.println(String.format(App.ERR_DELTA_APPEND, App.FILE_DELTA));
            }
        } catch (IOException exc) {
            File tmpDelta = deltaFile.toFile();
            if (!tmpDelta.exists()) {
                try {
                    if (!tmpDelta.createNewFile()) {
                        System.err.println(String.format(App.ERR_DELTA_CREATE, App.FILE_DELTA));
                    }
                } catch (IOException e) {
                    System.err.println(String.format(App.ERR_DELTA_CREATE, App.FILE_DELTA));
                }
            }
        }
    }

    /**
     * Sends the specific file to Solr with the Solrj library. This
     * method will call the update/extract request that is in fact
     * Tika integrated in Solr. When the file has been sent, Tika will
     * extract and/or parse the file and index it in the Solr core.
     *
     * This method can print an exception when something went wrong.
     *
     * @param path Path to the file to open and send to Solr.
     */
    private void sendToSolr(Path path) {
        SolrServer solr = new HttpSolrServer(this.solrURI);
        // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! //
        // !!! PREFIX REQUEST WITH A SLASH! OTHERWISE YOU WILL NOT GET AN AWESOME GUITAR SOLO !!! //
        // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! //
        ContentStreamUpdateRequest up = new ContentStreamUpdateRequest("/update/extract");

        if (this.debug) {
            System.out.println();
            System.out.print(String.format(App.OUT_PUSHING, path.toString()));
        }

        try {
            up.addFile(path.toFile(), this.contentType);
            up.setParam("literal.id", this.replacePathFromMap(path));
            up.setParam("uprefix", "attr_"); // Set the extra metadata
            up.setParam("fmap.content", "text"); // text is the actual content for free-text search.
            up.setParam("defaultField", "text");

            up.setMethod(SolrRequest.METHOD.POST);

            up.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);
            solr.request(up);

            if (this.debug) {
                System.out.print(App.OUT_DONE);
            }
        } catch (Exception e) {
            System.err.print(String.format(App.ERR_GENERIC, e.getMessage()));
        }
    }

    /**
     * Entry point of this application. This will try to load
     * the properties file and when this is impossible, one way or
     * another, the application will gracefully crash. Otherwise
     * it will continue and parse EVERYTHING.
     *
     * @param args Arguments of the application. Not used at the moment.
     */
    public static void main(String[] args) {
        Properties properties = new Properties();

        try {
            properties.load(new FileInputStream(App.FILE_CONFIG));
        } catch (Exception e) {
            System.err.print(String.format(App.ERR_CONFIG, e.getMessage()));
            System.exit(0);
        }

        new App().doMain(properties);
    }
}