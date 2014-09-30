package com.logrit.spaceshipspotter;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import static us.monoid.web.Resty.*;
import us.monoid.json.JSONException;
import us.monoid.json.JSONObject;
import us.monoid.web.JSONResource;
import us.monoid.web.Resty;
import us.monoid.web.TextResource;

import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.ActionBar;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Build;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.location.LocationClient;

public class MainActivity extends ActionBarActivity implements
		SensorEventListener, GooglePlayServicesClient.ConnectionCallbacks,
        GooglePlayServicesClient.OnConnectionFailedListener {

	private TextView myText;
	private SensorManager mSensorManager;
	private Sensor mLight;
	private List<Sensor> sensors;
	private LocationClient mLocationClient;
	HashMap<String, Reading> readings;
	ArrayList<SyncPoint> syncPoints;
	private Timer timer;
	public static final String BASE_URL = "http://spaceshipspotter.logrit.com/api";

	boolean recording = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		LinearLayout layout = (LinearLayout) findViewById(R.id.container);

		myText = new TextView(this);
		myText.setMovementMethod(new ScrollingMovementMethod());

		layout.addView(myText);

		readings = new HashMap<String, Reading>();

		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mLight = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
		sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
		
		mLocationClient = new LocationClient(this, this, this);

		for (Sensor s : sensors) {
			this.log("Found sensor: " + s.getName());
		}
		
		syncPoints = new ArrayList<SyncPoint>();
	}

	public void log(String s) {
		myText.setText(s + "\n" + myText.getText());
		// If myText is > 100mb, just truncate it
		if (myText.getText().length() > (8 * 1024 * 1024)) {
			myText.setText(myText.getText().subSequence(0, 8 * 1024 * 1024));
		}

		Log.i("spaceshipfinder", s);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {
		// log("Received accuracy change: " + arg0.toString() + ", " + arg1);
	}

	@Override
	public void onSensorChanged(SensorEvent arg0) {
		// log("Received sensor change: " + arg0.toString());
		synchronized (readings) {
			Reading r = new Reading(arg0);
			//Log.i("spaceshipfinder", r.toString());
			readings.put(arg0.sensor.getName(), r);
		}

	}

	public void startRecording(View view) {
		if (recording) {
			return;
		}
		recording = true;
		log("Starting recording");
		
		mLocationClient.connect();

		// Start listening to all sensors
		for (Sensor s : sensors) {
			log("Requesting sensor " + s.getName());
			mSensorManager.registerListener(this, s,
					SensorManager.SENSOR_DELAY_NORMAL);
			log("Success!");
		}
		
		// Kick off the timer to log ever 5 seconds
		timer = new Timer();
		timer.schedule(new SyncTask(), 5000, 5000);
	}

	public void stopRecording(View view) {
		if (!recording) {
			return;
		}
		recording = false;
		log("Stopping recording");
		timer.cancel();
		mLocationClient.disconnect();
		mSensorManager.unregisterListener(this);
	}

	public void syncServer(View view) {
		log("Synchronizing with server");
		
		log("Stopping to recording to sync");
		this.stopRecording(view);
		
		Thread t = new Thread() {
			public void run() {
				Resty r = new Resty();
				synchronized (syncPoints) {
					for(SyncPoint sync : syncPoints) {
						try {
							// POST /reports
							// Create the JSON object
							JSONObject json;
							json = new JSONObject();
							json.put("lat", sync.lat);
							json.put("lon", sync.lon);
							json.put("timestamp", (new Timestamp(sync.timestamp)).toString());
							Integer report_id = (Integer)r.json(BASE_URL+"/reports/", content(json)).get("id");
							// POST /readings
							for(Reading reading : sync.r) {
								json = new JSONObject();
								json.put("sensor", reading.sensor);
								json.put("type", reading.type);
								json.put("accuracy", reading.accuracy);
								json.put("timestamp", (new Timestamp(reading.timestamp)).toString());
								json.put("report", report_id);
								
								Integer reading_id = (Integer)r.json(BASE_URL+"/readings/", content(json)).get("id");

								// POST /values
								for(float value : reading.values) {
									json = new JSONObject();
									json.put("reading", reading_id);
									json.put("value", value);
									r.json(BASE_URL+"/values/", content(json));
								}
							}
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}
		};
		
		t.start();
		try {
			t.join();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	class Reading {
		String sensor;
		int type;
		int accuracy;
		long timestamp;
		float[] values;

		Reading(SensorEvent event) {
			this.sensor = event.sensor.getName();
			this.type = event.sensor.getType();
			this.accuracy = event.accuracy;
			//! This is time, in ns, since uptime
			// Convert timestamp to ms
			//this.timestamp = (long) (event.timestamp);
			Date date = new Date();
			this.timestamp = date.getTime();
			this.values = event.values;
		}
		
		public String toString() {
			StringBuffer sb = new StringBuffer();
			sb.append("Reading from: ");
			sb.append(this.sensor);
			sb.append("; type: "+type);
			sb.append("; accuracy: "+accuracy);
			sb.append("; timestamp: "+(new Timestamp(timestamp)).toString());
			sb.append("; values: (");
			
			for(float v : values) {
				sb.append(" " + v + " ");
			}
			sb.append(")");
			
			return sb.toString();
		}
	}

	class SyncPoint {
		ArrayList<Reading> r;
		long timestamp;
		double lat;
		double lon;

		SyncPoint() {
			synchronized (readings) {
				r = new ArrayList<Reading>(readings.values());
				Location mCurrentLocation = mLocationClient.getLastLocation();
				this.lat = mCurrentLocation.getLatitude();
				this.lon = mCurrentLocation.getLongitude();
				this.timestamp = mCurrentLocation.getTime();
				
				readings.clear();
			}
		}
		
		public String toString() {
			return "SyncPoint: (" + lat + ", " + lon + "), " + r.size() + " readings";
		}
	}
	
	class SyncTask extends TimerTask {

		@Override
		public void run() {
			// TODO Auto-generated method stub
			// Can't log from here without some sort of queue :(
			Log.i("spaceshipfinder", "Recording data points!");
			synchronized(syncPoints) {
				SyncPoint s = new SyncPoint();
				Log.i("spaceshipfinder", s.toString());
				syncPoints.add(new SyncPoint());
			}
		}
		
	}

	/**
	 * Stuff from the location services tutorial Lots of copy-pasta
	 */
	private final static int CONNECTION_FAILURE_RESOLUTION_REQUEST = 9000;

	// Define a DialogFragment that displays the error dialog
	public static class ErrorDialogFragment extends DialogFragment {
		// Global field to contain the error dialog
		private Dialog mDialog;

		// Default constructor. Sets the dialog field to null
		public ErrorDialogFragment() {
			super();
			mDialog = null;
		}

		// Set the dialog to display
		public void setDialog(Dialog dialog) {
			mDialog = dialog;
		}

		// Return a Dialog to the DialogFragment.
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			return mDialog;
		}
	}

	/*
	 * Handle results returned to the FragmentActivity by Google Play services
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// Decide what to do based on the original request code
		switch (requestCode) {
		case CONNECTION_FAILURE_RESOLUTION_REQUEST:
			/*
			 * If the result code is Activity.RESULT_OK, try to connect again
			 */
			switch (resultCode) {
			case Activity.RESULT_OK:
				/*
				 * Try the request again
				 */
				break;
			}
		}
	}

	private boolean servicesConnected() {
		// Check that Google Play services is available
		int resultCode = GooglePlayServicesUtil
				.isGooglePlayServicesAvailable(this);
		// If Google Play services is available
		if (ConnectionResult.SUCCESS == resultCode) {
			// In debug mode, log the status
			Log.d("Location Updates", "Google Play services is available.");
			// Continue
			return true;
			// Google Play services was not available for some reason.
			// resultCode holds the error code.
		} else {
			// Get the error dialog from Google Play services
			Dialog errorDialog = GooglePlayServicesUtil.getErrorDialog(
					resultCode, this, CONNECTION_FAILURE_RESOLUTION_REQUEST);

			// If Google Play services can provide an error dialog
			if (errorDialog != null) {
				// Create a new DialogFragment for the error dialog
				ErrorDialogFragment errorFragment = new ErrorDialogFragment();
				// Set the dialog in the DialogFragment
				errorFragment.setDialog(errorDialog);
				// Show the error dialog in the DialogFragment
				errorFragment.show(getSupportFragmentManager(),
						"Location Updates");
			}
		}
		return false;
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
		log(":( I have no idea");
	}

	@Override
	public void onConnected(Bundle arg0) {
		log("GPS Connected");
		
	}

	@Override
	public void onDisconnected() {
		log("GPS Disconnected");
	}
}
