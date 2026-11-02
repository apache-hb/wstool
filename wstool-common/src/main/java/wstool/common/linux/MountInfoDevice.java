package wstool.common.linux;

/** The major and minor device numbers from a mountinfo entry. */
public record MountInfoDevice(int major, int minor) {}
