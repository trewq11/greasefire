package com.skrul.greasefire;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Generate {

  class MetadataHack {
    int fans;
    int posts;
    int reviews;
    int averageReviews;
    String description;
  }
  
  private File destDir;
  private IndexWriter includesIndex;
  private IndexWriter excludesIndex;
  private Connection conn;
  private PreparedStatement ps;

  public static final Logger logger = Logger.getLogger(Generate.class.getName());

  private String[] badIncludesList = new String[] { 
      "*",
      "http://*",
      "http*://*",
      "https://*",
      "http:*",
      "http://*/*",
      "http://*/",
      "http://www.*",
      "http://www*",
      "http*",
      "www.*",
      "http://*.*.*/*",
      "*.com*.com/",
      "*//",
      "*/",
      "*.*",
      "*://*",
      "*.*.*",
      "http://www.*.com/*",
      "http://*/*.php*",
      "http://www.*.*/*",
      "*.aspx*",
      "http://*.*/*",
      "https://*.*/*",
      "http://*.com/blog/*",
      "http://*.*.*",
      "http://*//*",
      "*pic*",
      "http*://*/*",
      "http://",
      "http://*.*/*/",
      "htt*://*",
      "h",
      "http://*.*",
      "http://*.*.*.*/*",
      "http://*.com/*",
      "http:",
      "http://*blog*",
      "http://**",
      "*.com*",
      "htt*://*.*.*/*",
      "a",
      "http://*.*.*/",
      "h*",
      "http*://*.*",
      "*://*/ads.php",
      "*://*/*",
      "*.*.*/*",
      ""
  };

  private Set<String> badIncludes;
  private Map<String, MetadataHack> metadata;
  
  public static void main(String[] args) throws Exception {

    logger.info("Scripts dir: " + args[0]);
    logger.info("Dest dir: " + args[1]);

    Generate g = new Generate(new File(args[1]));

    File dir = new File(args[0]);
    File[] files = dir.listFiles(new FileFilter() {
      public boolean accept(File pathname) {
        return pathname.getName().endsWith(".props");
      }
    });

    for (int i = 0; i < files.length; i++) {
      File propsFile = files[i];

      Properties p = new Properties();
      p.load(new FileInputStream(propsFile));
      int id = Integer.parseInt(p.getProperty("id"));

      File script = new File(dir, id + ".js");

      if (i % 1000 == 0) {
        logger.info("at " + i + " of " + files.length +", last id was " + id);
      }
      g.addFile(id, p, script);
    }
    g.finish();
  }

  public Generate(File destDir) throws Exception {
    this.destDir = destDir;

    includesIndex = new IndexWriter();
    excludesIndex = new IndexWriter();
    badIncludes = new HashSet<String>(Arrays.asList(badIncludesList));
    metadata = new HashMap<String, MetadataHack>();
    
    File dbFile = new File(destDir, "scripts.db");

    Class.forName("org.sqlite.JDBC");
    conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getPath());
    Statement stat = conn.createStatement();
    stat.executeUpdate("drop table if exists scripts;");
    stat.executeUpdate("create table scripts (id integer primary key, name text, installs integer, updated integer);");
    ps = conn.prepareStatement("insert into scripts values (?, ?, ?, ?);");
  }

  public void addFile(int id, Properties props, File file) throws Exception {

    Pattern p = Pattern.compile("^//\\s*@(\\S+)\\s+(.*)$");

    BufferedReader input = null;
    try {
      input = new BufferedReader(new FileReader(file));
    } catch (FileNotFoundException e) {
      logger.log(Level.INFO, "Error opening script", e);
      return;
    }

    String name = null;
    List<String> includes = new ArrayList<String>();
    List<String> excludes = new ArrayList<String>();

    String line;
    boolean end = false;
    while (!end && (line = input.readLine()) != null) {
      if (line.indexOf("==/UserScript==") >= 0) {
        end = true;
      } else {
        Matcher m = p.matcher(line);
        if (m.matches()) {
          String key = m.group(1).trim();
          String value = m.group(2).trim();
          if (key.equals("name")) {
            name = value;
          } else if (key.equals("include")) {
            if (badIncludes.contains(value)) {
              return;
            }
            includes.add(value);
          } else if (key.equals("exclude")) {
            excludes.add(value);
          }
        }
      }
    }

    if (name == null || includes.size() == 0) {
      return;
    }

    for (String include : includes) {
      includesIndex.addMatch(id, include);
    }

    for (String exclude : excludes) {
      excludesIndex.addMatch(id, exclude);
    }

    ps.setInt(1, id);
    ps.setString(2, name.trim());
    ps.setInt(3, Integer.parseInt(props.getProperty("installs")));
    ps.setLong(4, Long.parseLong(props.getProperty("updated")));
    ps.addBatch();

    // TODO(skrul): Move this stuff into the db when the firefox version is updated.
    MetadataHack md = new MetadataHack();
    md.fans = Integer.parseInt(props.getProperty("fans"));
    md.posts = Integer.parseInt(props.getProperty("posts"));
    md.reviews = Integer.parseInt(props.getProperty("reviews"));
    md.averageReviews = Integer.parseInt(props.getProperty("averageReviews"));
    md.description = props.getProperty("description");
    metadata.put(Integer.toString(id), md);
  }

  public void finish() throws Exception {
    conn.setAutoCommit(false);
    ps.executeBatch();
    conn.setAutoCommit(true);

    BufferedWriter scripts = new BufferedWriter(new FileWriter(new File(destDir, "scripts.txt")));
    PreparedStatement ps = conn.prepareStatement("select id, installs, updated, name from scripts");
    ResultSet rs = ps.executeQuery();
    while (rs.next()) {
      MetadataHack md = metadata.get(rs.getString(1));
      // id & installs & updated & fans & posts & reviews & averageReviews & name & description
      scripts.write(rs.getString(1));
      scripts.write('&');
      scripts.write(rs.getString(2));
      scripts.write('&');
      scripts.write(rs.getString(3));
      scripts.write('&');
      scripts.write(Integer.toString(md.fans));
      scripts.write('&');
      scripts.write(Integer.toString(md.posts));
      scripts.write('&');
      scripts.write(Integer.toString(md.reviews));
      scripts.write('&');
      scripts.write(Integer.toString(md.averageReviews));
      scripts.write('&');
      scripts.write(URLEncoder.encode(rs.getString(4), "utf8"));
      scripts.write('&');
      scripts.write(URLEncoder.encode(md.description, "utf8"));
      scripts.newLine();
    }
    rs.close();
    conn.close();
    scripts.close();
    
    File includesFile = new File(destDir, "include.dat");
    FileOutputStream fos = new FileOutputStream(includesFile);
    includesIndex.serialize(fos);
    fos.close();

    File excludesFile = new File(destDir, "exclude.dat");
    fos = new FileOutputStream(excludesFile);
    excludesIndex.serialize(fos);
    fos.close();

    PNGOutputStream pos = new PNGOutputStream(new File(destDir, "include.png"));
    includesIndex.serialize(pos);
    pos.close();

    pos = new PNGOutputStream(new File(destDir, "exclude.png"));
    excludesIndex.serialize(pos);
    pos.close();
}
  
  public static void search(Node n, String s, int pos, Set<Integer> matches) {

    if (n.ids != null) {
      matches.addAll(n.ids);
    }

    if (pos > s.length() - 1) {
      return;
    }

    char current = s.charAt(pos);
    Node node = n.children.get(current);
    if (node != null) {
      search(node, s, pos + 1, matches);
    }

    node = n.children.get('*');
    if (node != null) {
      for (int i = pos; i < s.length(); i++) {
        search(node, s, i, matches);
      }
    }
  }

  public static void dump(Node n, String s) {

    if (n.ids != null) {
      System.out.println(s + " " + n.ids);
    }

    for (Character c : n.children.keySet()) {
      Node node = n.children.get(c);
      dump(node, s + c);
    }

  }
}
