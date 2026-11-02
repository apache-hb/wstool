package wstool.common.linux;

import com.opencsv.bean.CsvBindAndJoinByPosition;
import com.opencsv.bean.CsvBindByPosition;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.collections4.ListValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

/**
 * A typed row from Linux mountinfo.
 *
 * <p>The format has six fixed fields, followed by zero or more optional fields, a separator, and
 * three trailing fields.
 *
 * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5)</a>
 */
public final class MountInfoEntry {
    /**
     * Mount ID (field 1).
     *
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5), field 1</a>
     */
    @CsvBindByPosition(position = 0)
    private String mountId;

    /**
     * Parent mount ID (field 2).
     *
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5), field 2</a>
     */
    @CsvBindByPosition(position = 1)
    private String parentMountId;

    /**
     * Device major and minor numbers (field 3).
     *
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5), field 3</a>
     */
    @CsvBindByPosition(position = 2)
    private String device;

    /**
     * Filesystem root (field 4).
     *
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5), field 4</a>
     */
    @CsvBindByPosition(position = 3)
    private String root;

    /**
     * Mount point (field 5).
     *
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5), field 5</a>
     */
    @CsvBindByPosition(position = 4)
    private String mountPoint;

    /**
     * Per-mount options (field 6).
     *
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5), field 6</a>
     */
    @CsvBindByPosition(position = 5)
    private String mountOptions;

    /**
     * Optional fields, separator, filesystem type, mount source, and superblock options (fields
     * 7–11). The optional fields are variable length, so the trailing fields are relative to the
     * separator. Optional fields use the form {@code tag[:value]}.
     *
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5), fields 7–11</a>
     */
    @CsvBindAndJoinByPosition(position = "6-", elementType = String.class)
    private ListValuedMap<Integer, String> optionalAndTrailingFields =
            new ArrayListValuedHashMap<>();

    public MountInfoEntry() {}

    /**
     * Returns this mount's numeric ID.
     *
     * @return the mount ID
     */
    public int mountId() {
        return Integer.parseInt(mountId);
    }

    /**
     * Returns this mount's parent's numeric ID.
     *
     * @return the parent mount ID
     */
    public int parentMountId() {
        return Integer.parseInt(parentMountId);
    }

    /**
     * Returns the device's major and minor numbers.
     *
     * @return the device number
     */
    public MountInfoDevice device() {
        String[] numbers = device.split(":", 2);
        if (numbers.length != 2) {
            throw new IllegalStateException("Mountinfo entry has an invalid device number");
        }
        return new MountInfoDevice(Integer.parseInt(numbers[0]), Integer.parseInt(numbers[1]));
    }

    /**
     * Returns the filesystem root path for this mount.
     *
     * @return the root path
     */
    public Path root() {
        return Path.of(unescapeMountPath(root));
    }

    /**
     * Returns this mount's mount point.
     *
     * @return the mount point path
     */
    public Path mount() {
        return Path.of(unescapeMountPath(mountPoint));
    }

    /**
     * Returns the comma-separated per-mount options.
     *
     * @return the per-mount options
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5)</a>
     */
    public List<String> mountOptions() {
        return Arrays.asList(mountOptions.split(","));
    }

    /**
     * Returns optional mount tags that occur before the separator.
     *
     * @return a map from tag names to values; tags without values map to an empty string
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5)</a>
     */
    public Map<String, String> tags() {
        int separator = separatorPosition();
        return optionalAndTrailingFields.entries().stream()
                .filter(entry -> entry.getKey() < separator)
                .map(
                        entry -> {
                            String[] tag = entry.getValue().split(":", 2);
                            return Map.entry(tag[0], tag.length == 2 ? tag[1] : "");
                        })
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (existing, replacement) -> replacement));
    }

    /**
     * Returns the filesystem type field after the separator.
     *
     * @return the filesystem type
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5)</a>
     */
    public String type() {
        return trailingField(1);
    }

    /**
     * Returns the mount source field after the separator.
     *
     * @return the mount source
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5)</a>
     */
    public String mountSource() {
        return trailingField(2);
    }

    /**
     * Returns the comma-separated superblock options after the separator.
     *
     * @return the superblock options
     * @see <a href="https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html">proc_pid_mountinfo(5)</a>
     */
    public List<String> superOptions() {
        return Arrays.asList(trailingField(3).split(","));
    }

    private String trailingField(int relativePosition) {
        return optionalAndTrailingFields
                .get(separatorPosition() + relativePosition)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Mountinfo entry is missing a field after its separator"));
    }

    private int separatorPosition() {
        return optionalAndTrailingFields.entries().stream()
                .filter(entry -> entry.getValue().equals("-"))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Mountinfo entry has no separator"));
    }

    private static String unescapeMountPath(String path) {
        return path.replace("\\040", " ")
                .replace("\\011", "\t")
                .replace("\\012", "\n")
                .replace("\\134", "\\");
    }
}
