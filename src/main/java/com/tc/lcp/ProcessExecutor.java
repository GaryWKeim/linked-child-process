package com.tc.lcp;

import com.sun.jna.platform.win32.Kernel32;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

public abstract class ProcessExecutor {

  public static ProcessExecutor exec(String[] command, Map<String, String> env, File workingDir) throws IOException {
    if (isWindows()) {
      fixupWindowsEnvironment(env);
      return new JavaWithWin32ShortenedPathProcessExecutor(command, env, workingDir);
    } else {
      return new JavaProcessExecutor(command, env, workingDir);
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").indexOf("Windows") >= 0;
  }

  private static void fixupWindowsEnvironment(Map<String, String> env) {
    // A bunch of name lookup stuff will fail w/o setting SYSTEMROOT. Also, if
    // you have apple's rendevous/bonjour
    // client installed, it needs to be in the PATH such that dnssd.dll will
    // be found when using DNS

    if (!env.containsKey("SYSTEMROOT")) {
      String root = ":\\Windows";
      char i;
      for (i = 'c'; i <= 'z'; i++) {
        if (new File(i + root).exists()) {
          root = i + root;
          break;
        }
      }
      if (i > 'z') throw new RuntimeException("Can't find windir");
      env.put("SYSTEMROOT", root);
    }

    String crappleDirs = "C:\\Program Files\\Rendezvous\\" + File.pathSeparator + "C:\\Program Files\\Bonjour\\";

    if (!env.containsKey("PATH")) {
      env.put("PATH", crappleDirs);
    } else {
      String path = env.get("PATH");
      path = path + File.pathSeparator + crappleDirs;
      env.put("PATH", path);
    }
  }

  public abstract void destroy();

  public abstract InputStream getInputStream();

  public abstract String[] getCommand();

  public abstract InputStream getErrorStream();

  public abstract OutputStream getOutputStream();

  public abstract int exitValue();

  public abstract int waitFor() throws InterruptedException;

  static class JavaProcessExecutor extends ProcessExecutor {
    private Process process;
    private String[] command;

    JavaProcessExecutor(String[] command, Map<String, String> env, File workingDir) throws IOException {
      this.command = command;
      this.process = Runtime.getRuntime().exec(command, makeEnv(env), workingDir);
    }

    private static String[] makeEnv(Map<String, String> env) {
      int i = 0;
      String[] rv = new String[env.size()];
      for (Iterator<String> iter = env.keySet().iterator(); iter.hasNext(); i++) {
        String key = iter.next();
        rv[i] = key + "=" + env.get(key);
      }
      return rv;
    }


    @Override
    public void destroy() {
      process.destroy();
    }

    @Override
    public InputStream getInputStream() {
      return process.getInputStream();
    }

    @Override
    public String[] getCommand() {
      return command;
    }

    @Override
    public InputStream getErrorStream() {
      return process.getErrorStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return process.getOutputStream();
    }

    @Override
    public int exitValue() {
      // Process.exitValue() throws an exception if not yet terminated, so we know
      // it's terminated now.
      return process.exitValue();
    }

    @Override
    public int waitFor() throws InterruptedException {
      return process.waitFor();
    }
  }

  static class JavaWithWin32ShortenedPathProcessExecutor extends JavaProcessExecutor {
    JavaWithWin32ShortenedPathProcessExecutor(String[] command, Map<String, String> env, File workingDir) throws IOException {
      super(command, env, shortenedPath(workingDir));
    }

    /**
     * Transform a file path on windows to its shortened version (i.e.: C:\Program Files\My Folder\... -> C:\Progra~1\MyFold~1\...).
     * Since a process' path cannot be longer than 260 chars (MAX_PATH) or the CreateProcess syscall will fail with
     * ERROR_DIRECTORY (267) let's hope that makes the path short enough.
     * Beware the "user.dir" system property in the child process as it may not match char-to-char the path specified by the parent!
     */
    private static File shortenedPath(File workingDir) throws IOException {
      String absoluteLongUncPrefixedPath = "\\\\?\\" + workingDir.getAbsolutePath();
      char[] buffer = new char[64];
      int shortPathLength = Kernel32.INSTANCE.GetShortPathName(absoluteLongUncPrefixedPath, buffer, buffer.length);
      if (shortPathLength > buffer.length) {
        // buffer is too small, realloc and retry
        buffer = new char[shortPathLength];
        shortPathLength = Kernel32.INSTANCE.GetShortPathName(absoluteLongUncPrefixedPath, buffer, buffer.length);
      }
      if (shortPathLength < 1) {
        throw new IOException("GetShortPathName error : " + Kernel32.INSTANCE.GetLastError());
      }

      int offset = 0;
      if (shortPathLength > 4 &&
          buffer[0] == '\\' &&
          buffer[1] == '\\' &&
          buffer[2] == '?' &&
          buffer[3] == '\\') {
        offset = 4;
      }

      String shortenedAbsolutePath = new String(buffer, offset, shortPathLength - offset);
      return new File(shortenedAbsolutePath);
    }
  }

}
