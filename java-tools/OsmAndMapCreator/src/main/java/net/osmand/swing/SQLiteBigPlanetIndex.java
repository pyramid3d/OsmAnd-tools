package net.osmand.swing;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import net.osmand.PlatformUtil;
import net.osmand.map.ITileSource;
import net.osmand.map.TileSourceManager.TileSourceTemplate;

import org.apache.commons.logging.Log;


public class SQLiteBigPlanetIndex {
	private static final Log log = PlatformUtil.getLog(SQLiteBigPlanetIndex.class);

	private static final int BATCH_SIZE = 50;
	private static boolean bigPlanet = false;

	public static void createSQLiteDatabase(File dirWithTiles, String regionName, ITileSource template) throws SQLException, IOException {
		long now = System.currentTimeMillis();
		try {
			Class.forName("org.sqlite.JDBC"); //$NON-NLS-1$
		} catch (ClassNotFoundException e) {
			log.error("Illegal configuration", e); //$NON-NLS-1$
			throw new IllegalStateException(e);
		}
		File fileToWrite = new File(dirWithTiles, regionName + "." + template.getName() + ".sqlitedb");
		fileToWrite.delete();
		Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fileToWrite.getAbsolutePath()); //$NON-NLS-1$
		Statement statement = conn.createStatement();
		statement.execute("CREATE TABLE tiles (x int, y int, z int, s int, image blob, time long, PRIMARY KEY (x,y,z,s))");
		statement.execute("CREATE INDEX IND on tiles (x,y,z,s)");
		statement.execute("CREATE TABLE info(tilenumbering,minzoom,maxzoom,timecolumn,url,rule,referer)");
		statement.execute("CREATE TABLE android_metadata (locale TEXT)");
		statement.close();


		PreparedStatement pStatement = conn.prepareStatement("INSERT INTO INFO VALUES(?,?,?,?,?,?,?)");
		String tileNumbering = bigPlanet ? "BigPlanet" : "simple";
		pStatement.setString(1, tileNumbering);
		int minNormalZoom = bigPlanet ? 17 - template.getMaximumZoomSupported() : template.getMinimumZoomSupported();
		int maxNormalZoom = bigPlanet ? 17 - template.getMinimumZoomSupported() : template.getMaximumZoomSupported();
		pStatement.setInt(2, minNormalZoom);
		pStatement.setInt(3, maxNormalZoom);
		pStatement.setString(4, "yes");
		pStatement.setString(5, ((TileSourceTemplate) template).getUrlTemplate());
		pStatement.setString(6, ((TileSourceTemplate) template).getRule());
		pStatement.setString(7, ((TileSourceTemplate) template).getReferer());
		pStatement.execute();
		log.info("Info table" + tileNumbering + "maxzoom = " + maxNormalZoom + " minzoom = " + minNormalZoom + " timecolumn = yes"
				+ " url = " + ((TileSourceTemplate) template).getUrlTemplate());
		pStatement.close();


		conn.setAutoCommit(false);
		pStatement = conn.prepareStatement("INSERT INTO tiles VALUES (?, ?, ?, ?, ?, ?)");
		int ch = 0;
		// be attentive to create buf enough for image
		byte[] buf;
		int maxZoom = 17;
		int minZoom = 1;

		File rootDir = new File(dirWithTiles, template.getName());
		for(File z : rootDir.listFiles()){
			try {
				int zoom = Integer.parseInt(z.getName());
				for(File xDir : z.listFiles()){
					try {
						int x = Integer.parseInt(xDir.getName());
						for(File f : xDir.listFiles()){
							if(!f.isFile()){
								continue;
							}
							try {
								int i = f.getName().indexOf('.');
								int y = Integer.parseInt(f.getName().substring(0, i));
								buf = new byte[(int) f.length()];
								if(zoom > maxZoom){
									maxZoom = zoom;
								}
								if(zoom < minZoom){
									minZoom = zoom;
								}

								FileInputStream is = new FileInputStream(f);
								int l = 0;
								try {
									l = is.read(buf);
								} finally {
									is.close();
								}
								if (l > 0) {
									pStatement.setInt(1, x);
									pStatement.setInt(2, y);
									pStatement.setInt(3, bigPlanet ? 17 - zoom : zoom);
									pStatement.setInt(4, 0);
									pStatement.setBytes(5, buf);
									pStatement.setLong(6, f.lastModified());
									pStatement.addBatch();
									ch++;
									if (ch >= BATCH_SIZE) {
										pStatement.executeBatch();
										ch = 0;
									}
								}

							} catch (NumberFormatException e) {
							}
						}
					} catch (NumberFormatException e) {
					}
				}

			} catch (NumberFormatException e) {
			}

		}

		if (ch > 0) {
			pStatement.executeBatch();
			ch = 0;
		}

		pStatement.close();
		conn.commit();
		conn.close();
		log.info("Index created " + fileToWrite.getName() + " " + (System.currentTimeMillis() - now) + " ms");
	}

}
