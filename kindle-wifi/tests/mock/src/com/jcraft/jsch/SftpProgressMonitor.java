package com.jcraft.jsch;
public interface SftpProgressMonitor {
 void init(int op,String src,String dest,long max);
 boolean count(long bytes);
 void end();
}
