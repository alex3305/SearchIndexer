import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;

/**
 * DeltaFile class that is used to store a path and the last
 * modified time. The objects derived from this path are in
 * fact serialized from the given delta file in the App class.
 *
 * @author alexh
 */
public class DeltaFile {

    /** Last modified time. */
    private FileTime lastModified;

    /** Path that it is about. */
    private Path path;

    /**
     * Constructs a new DeltaFile from the given line. The
     * delimiter from the App class is used to split the
     * absolute path and file time in milliseconds.
     *
     * @param line Line to deserialize to an object.
     */
    public DeltaFile(String line) {
        String[] vars = line.split(App.DELIMITER_SEPERATOR);

        try {
            this.path = Paths.get(vars[0]);
            this.lastModified = FileTime.fromMillis(Long.valueOf(vars[1]));
        } catch (Exception e) {
            // Error reading this line, no worries. Just skip it.
        }
    }

    /**
     * Gets the last modified time of the given path.
     * @return Last modified time.
     */
    public FileTime getLastModified() {
        return this.lastModified;
    }

    /**
     * Gets the Path object that is deserialized from the
     * absolute path.
     *
     * @return Path.
     */
    public Path getPath() {
        return this.path;
    }
}