package com.jcraft.jsch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SftpATTRS {
    private final Path path;
    SftpATTRS(Path path) { this.path = path; }
    public long getSize() throws IOException { return Files.size(path); }
    public boolean isDir() { return Files.isDirectory(path); }
    public boolean isReg() { return Files.isRegularFile(path); }
    public boolean isLink() { return Files.isSymbolicLink(path); }
    public int getMTime() {
        try { return (int)(Files.getLastModifiedTime(path).toMillis() / 1000); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
