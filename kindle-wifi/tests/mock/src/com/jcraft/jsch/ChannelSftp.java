package com.jcraft.jsch;
import java.io.*;import java.nio.file.*;import java.util.*;
public class ChannelSftp {
 public static final int SSH_FX_NO_SUCH_FILE=2;
 private Path dir;
 public boolean corrupt;
 public ChannelSftp(Path dir){this.dir=dir;}
 public void cd(String value) throws IOException{Path x=Paths.get(value); if(!Files.isDirectory(x))throw new IOException("Missing folder");dir=x;}
 public String pwd(){return dir.toString();}
 public String realpath(String value)throws IOException{return path(value).toRealPath().toString();}
 public String getExtension(String name){return null;}
 public boolean isConnected(){return true;}
 public static class LsEntry {
  private final Path path;
  LsEntry(Path p){path=p;}
  public String getFilename(){return path.getFileName().toString();}
  public SftpATTRS getAttrs(){return new SftpATTRS(path);}
 }
 private Path path(String raw){return Paths.get(raw.replace("\\*","*").replace("\\?","?").replace("\\\\","\\"));}
 public SftpATTRS lstat(String path) throws SftpException {Path p=path(path);if(!p.isAbsolute())p=dir.resolve(p);if(!Files.exists(p))throw new SftpException(SSH_FX_NO_SUCH_FILE,"absent");return new SftpATTRS(p);}
 public void put(InputStream in, String name, SftpProgressMonitor monitor) throws Exception {
  byte[] buf=new byte[64*1024];int n;Path target=dir.resolve(name);try(OutputStream out=Files.newOutputStream(target)) {
   while((n=in.read(buf))>=0){out.write(buf,0,n);if(!monitor.count(n))throw new IOException("cancelled");}
  }
  if(corrupt){byte[] bytes=Files.readAllBytes(target); bytes[12]^=0x7; Files.write(target,bytes);}
 }
 public InputStream get(String remote)throws IOException{Path p=path(remote);if(!p.isAbsolute())p=dir.resolve(p);return Files.newInputStream(p);}
 public void rename(String src,String dst)throws IOException{Files.move(dir.resolve(path(src)),dir.resolve(path(dst)));}
 public void rm(String src)throws IOException{Files.deleteIfExists(dir.resolve(path(src)));}
 public void mkdir(String src)throws IOException,SftpException{Files.createDirectory(dir.resolve(path(src)));}
 public Vector<LsEntry> ls(String raw)throws IOException{Vector<LsEntry> v=new Vector<>();try(java.util.stream.Stream<Path> p=Files.list(dir.resolve(path(raw)))){p.forEach(x->v.add(new LsEntry(x)));}return v;}
}
