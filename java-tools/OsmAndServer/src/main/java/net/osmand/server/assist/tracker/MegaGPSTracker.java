package net.osmand.server.assist.tracker;



import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.osmand.server.assist.OsmAndAssistantBot;
import net.osmand.server.assist.TrackerConfiguration;
import net.osmand.server.assist.convers.UserChatIdentifier;

import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.exceptions.TelegramApiException;

import com.j256.simplecsv.common.CsvColumn;
import com.j256.simplecsv.processor.CsvProcessor;

@Component
public class MegaGPSTracker {

	protected ThreadPoolExecutor exe;
	protected CloseableHttpClient httpclient;
	protected RequestConfig requestConfig;
	private static final String BASE_URL = "http://mega-gps.org/api3";
	public static final int SOCKET_TIMEOUT = 15 * 1000;
	private static final org.apache.commons.logging.Log LOG = LogFactory.getLog(MegaGPSTracker.class);
	
	public static class MegaGPSDevice {
		@CsvColumn(columnName="id")
		String id;
		@CsvColumn(columnName="name", mustBeSupplied=false)
		String name;
		
		@CsvColumn(columnName="extra", mustBeSupplied=false)
		String extra;
		
		@CsvColumn(columnName="tlast", mustBeSupplied=false)
		long timeLastReceived;
		
		@CsvColumn(columnName="tvalid", mustBeSupplied=false)
		long timeLastValid;
		
		@CsvColumn(columnName="tarc", mustBeSupplied=false)
		long timeLastInContinuousArchive;
		
		@CsvColumn(columnName="lat", mustBeSupplied=false)
		int lat;
		
		@CsvColumn(columnName="lng", mustBeSupplied=false)
		int lng;
		
		@CsvColumn(columnName="speed", mustBeSupplied=false)
		float speed;
		
		@CsvColumn(columnName="alt", mustBeSupplied=false)
		float altitude;
		
		@CsvColumn(columnName="azi", mustBeSupplied=false)
		float azi;
		
		@CsvColumn(columnName="sat", mustBeSupplied=false)
		int sattelites;
		
		@CsvColumn(columnName="temp", mustBeSupplied=false)
		int temparature;
		
		public float getLongitude() {
			return lng / 1000000.0f;
		}
		
		public float getLatitude() {
			return lat / 1000000.0f;
		}

		@Override
		public String toString() {
			return "MegaGPSDevice (id=" + id + ", name=" + name + ", extra=" + extra + ", timeLastReceived="
					+ timeLastReceived + ", timeLastValid=" + timeLastValid + ", timeLastInContinuousArchive="
					+ timeLastInContinuousArchive + ", lat=" + lat + ", lng=" + lng + ", speed=" + speed
					+ ", altitude=" + altitude + ", azi=" + azi + ", sattelites=" + sattelites + ", temparature="
					+ temparature + ")";
		}
		
		
	}

	public MegaGPSTracker() {
		this.exe = new ThreadPoolExecutor(1, 8, 1L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<Runnable>());
        httpclient = HttpClientBuilder.create()
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .setConnectionTimeToLive(SOCKET_TIMEOUT, TimeUnit.MILLISECONDS)
                .setMaxConnTotal(20)
                .build();
        requestConfig = RequestConfig.copy(RequestConfig.custom().build())
                .setSocketTimeout(SOCKET_TIMEOUT)
                .setConnectTimeout(SOCKET_TIMEOUT)
                .setConnectionRequestTimeout(SOCKET_TIMEOUT).build();
	}

	private List<MegaGPSDevice> getDevices(TrackerConfiguration c, String apiMethod, String deviceId) throws IOException, ClientProtocolException {
		String url = BASE_URL;
		HttpPost httppost = new HttpPost(url);
		httppost.setConfig(requestConfig);
		httppost.addHeader("charset", StandardCharsets.UTF_8.name());

		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("s", c.token));
		params.add(new BasicNameValuePair("c", apiMethod));
		params.add(new BasicNameValuePair("i", deviceId));

		httppost.setEntity(new UrlEncodedFormEntity(params));
		List<MegaGPSDevice> devs = Collections.emptyList();
		try (CloseableHttpResponse response = httpclient.execute(httppost)) {
			HttpEntity ht = response.getEntity();
			BufferedHttpEntity buf = new BufferedHttpEntity(ht);
			String result = EntityUtils.toString(buf, StandardCharsets.UTF_8);
			devs = parseDevices(result);
		}
		return devs;
	}
	
	public void retrieveMyDevices(OsmAndAssistantBot bot, TrackerConfiguration c, UserChatIdentifier ucid, int cfgOrder) {
		exe.submit(new Runnable() {
			@Override
			public void run() {
				try {
					List<MegaGPSDevice> devs = getDevices(c, "0", "0");
					SendMessage msg = new SendMessage();
					msg.setChatId(ucid.getChatId());
					printDevices(msg, c, devs, cfgOrder, true);
					bot.sendTextMsg(msg);
				} catch (Exception e) {
					if (!(e instanceof TelegramApiException)) {
						try {
							bot.sendTextMsg(new SendMessage(ucid.getChatId(), 
									"Error while retrieving list:" + e.getMessage()));
						} catch (TelegramApiException e1) {
						}
					}
					LOG.warn("Error while retrieving devices list", e);
				}
			}

		});
	}


	protected String printDevices(SendMessage msg, TrackerConfiguration c, List<MegaGPSDevice> devs, int cfgOrder, boolean keyboard) {
		InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
		StringBuilder s = new StringBuilder();
		if(cfgOrder != 0) {
			s.append("Devices in '").append(c.trackerName).append("':");
		} else {
			s.append("Available devices:");
		}
		s.append('\n');
		int k = 1;
		for(MegaGPSDevice d : devs) {
			StringBuilder txt = new StringBuilder(40); 
			txt.append(cfgOrder == 0 ? "" : (cfgOrder +".")).append(k).append(k < 10 ? " " : "").append("  ");
			if(d.name != null) {
				txt.append(d.name);
			}
			if(d.id != null) {
				txt.append(" (").append(d.id).append(")");
			}
//			if(k < 3) {
//				s.append(d.toString());
//			}
			if(!keyboard) {
				s.append(txt).append('\n');
			} else {
				ArrayList<InlineKeyboardButton> lt = new ArrayList<InlineKeyboardButton>();
				InlineKeyboardButton button = new InlineKeyboardButton(txt.toString());
				button.setCallbackData("device." + c.id + "." + d.id);
				lt.add(button);
				markup.getKeyboard().add(lt);
			}
			k++;
		}
		if(keyboard) {
			msg.setReplyMarkup(markup);
		}
		msg.setText(s.toString());
		return s.toString();
	}


	protected List<MegaGPSDevice> parseDevices(String result) {
		List<MegaGPSDevice> devices = new ArrayList<MegaGPSTracker.MegaGPSDevice>();
		CsvProcessor<MegaGPSDevice> csvProcessor = new CsvProcessor<MegaGPSDevice>(MegaGPSDevice.class);
		csvProcessor.setFlexibleOrder(true);
		csvProcessor.setColumnSeparator(';');
		csvProcessor.setAllowPartialLines(true);
		csvProcessor.setHeaderValidation(true);
		csvProcessor.setIgnoreUnknownColumns(true);
		try {
			devices = csvProcessor.readAll(new StringReader(result), null);
		} catch (ParseException e) {
			LOG.warn(e.getMessage(), e);
		} catch (IOException e) {
			LOG.warn(e.getMessage(), e);
		}
		return devices;
	}

	public boolean accept(TrackerConfiguration c) {
		return c.trackerId.equals("http://mega-gps.org/") || c.trackerId.equals("mega-gps.org");
	}
}