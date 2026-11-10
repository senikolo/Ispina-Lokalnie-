package com.jcraft.jsch;
public class SftpException extends Exception {
 public int id;
 public SftpException(int id,String s){ super(s); this.id=id; }
}
