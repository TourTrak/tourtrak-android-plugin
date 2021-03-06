package edu.rit.se.trafficanalysis.tracking;

import edu.rit.se.tourtrak.R;
//import edu.rit.se.se561.trafficanalysis.R;
import edu.rit.se.trafficanalysis.TourConfig;
//import edu.rit.se.se561.trafficanalysis.util.NotificationHelper;
import edu.rit.se.trafficanalysis.reminders.TourFinishReminderAlarm;
import edu.rit.se.trafficanalysis.util.Util;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class TrackingService extends Service implements LocationListener {
	private static final String TAG = TrackingService.class.getSimpleName();
	/**
	 * Actions sent to the service when it is "started"
	 */
	public static final String ACTION_START_TRACKING = "start";
	public static final String ACTION_STOP_TRACKING = "stop";
	public static final String ACTION_PAUSE_TRACKING = "pause";
	
	public static boolean isBeta = false;

	
	private static WakeLock mWakeLock;
	/**
	 * The location manager to request updates from.
	 */
	private LocationManager mLocationManager;

	/**
	 * Whether or not the service is currently tracking.
	 */
	private static boolean isTracking;

	private PendingIntent mPassivePendingIntent;
	private BroadcastReceiver mRequestReceiver;
	private BroadcastReceiver mLocationReceiver;
	private BroadcastReceiver mBatteryWatchDogReceiver;

	/**
	 * Used to update clients of the tour and tracking state.
	 */
	private StateBroadcaster mStateBroadcaster;

	/**
	 * The current race/rider configuration.
	 */
	private TourConfig mTourConfig;

	/**
	 * Used to keep track of the length of time the user has been tracking.
	 */
	private static long startTrackTime = 0;

	@Override
	public void onCreate() {
		
		mTourConfig = new TourConfig(this);
		isTracking = false;
		mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		
		mPassivePendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(
				LocationManager.KEY_LOCATION_CHANGED),
				PendingIntent.FLAG_UPDATE_CURRENT);

		mStateBroadcaster = new StateBroadcaster(getApplicationContext());
		mStateBroadcaster.beforeTracking();

	}

	/**
	 * Cleans up all timers and stops tracking user's location before
	 * destroying itself. 
	 */
	@Override
	public void onDestroy() {
		Log.i(TAG, "Tracking Service Stopped");
		stopTracking();
		if (mTourConfig.isTourOver()) {
			mStateBroadcaster.afterTour();
		}
		super.onDestroy();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		
		try {
			if(intent != null) {
				String action = intent.getAction();
				if (action != null) {
					if (action.equals(ACTION_START_TRACKING)) {
						if (!isTracking) {
							startTracking();
						}
					} else if (action.equals(ACTION_STOP_TRACKING)) {
						if (isTracking) {
							stopTracking();
						} 
					} else if (action.equals(ACTION_PAUSE_TRACKING)){
						if (isTracking) {
							pauseTracking();
						}
					}
				}
			}
			else {
				if (!isTracking) {
					startTracking();
				}
			}
		} finally {
			releaseWakeLock(this);
		}
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void setAlarms() {
		LocationRequestAlarm.setAlarm(this,
				TimingController.getLocationRequestDelay(this));
		LocationDeliverAlarm.setAlarm(this,
				(long) (TimingController.getLocationDeliveryDelay(this)
				 * (Math.random() + 0.25)));

//		TourFinishReminderAlarm.setAlarm(this);
//		EndTrackingAlarm.setAlarm(this);
	}

	private void cancelAlarms() {
		Log.d(TAG, "Cancelling all alarms!");
		LocationRequestAlarm.cancelAlarm(this);
		//TourFinishReminderAlarm.cancelAlarm(this);
		LocationDeliverAlarm.cancelAlarm(this);
	}

	private void startTracking() {
		Log.i(TAG, "START TRACKING CALLED!");
		if (isTracking) {
			Log.i(TAG, "We were already tracking!");
			return;
		}
		isTracking = true;
		Log.i(TAG, "Tracking Service Started");

		if (TrackingService.isBeta){
			StartTrackingAlarmBeta.cancelAlarm(this);
		} else{
			StartTrackingAlarm.cancelAlarm(this);
		}
	
		startTrackTime = System.currentTimeMillis();
		
		TimingController.recalculateBatteryUsage(this);

		updateNotifications();
		registerReceivers();
		setAlarms();
		
		// if tracking had stopped, reinitalize location manager
		if(mLocationManager == null){
			mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		}
		
		// Request locations.
		mLocationManager.requestLocationUpdates(
				LocationManager.PASSIVE_PROVIDER,
				getResources().getInteger(R.integer.passiveLocDelayMs),
				getResources().getInteger(R.integer.passiveLocDistanceM),
				mPassivePendingIntent);

		mLocationManager.requestLocationUpdates(
				LocationManager.NETWORK_PROVIDER, Long.MAX_VALUE,
				Long.MAX_VALUE, this);

		mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
				Long.MAX_VALUE, Long.MAX_VALUE, this);

		sendBroadcast(new Intent(
				LocationRequestAlarm.REQUEST_LOCATION_UPDATE_ACTION));

		mStateBroadcaster.trackingStarted();
	}
	
	/**
	 * Stops all tracking and location delivery to server
	 * 
	 * @param cancel - if true, we gracefully kill everything (aka tour has ended).
	 */
	private void stopAllTracking(){
		Log.i(TAG, "STOP ALL TRACKING CALLED!");
		if (!isTracking) {
			Log.i(TAG, "Already not tracking!");
			return;
		}
		
		isTracking = false;
		mTourConfig.addTotalTrackTime(System.currentTimeMillis()
				- startTrackTime);
		startTrackTime = 0;

		updateNotifications();
		unregisterReceivers();
		cancelAlarms();
		
		mStateBroadcaster.trackingPaused();
		
		// Remove all updates on all pending intents
		// Used in LocationRequestIntentService and LocationSaveIntentService
		// Sufficient to recreate the pending intents with same intents to cancel them and remove updates from location manager!
		
		// Remove updates & cancel from TrackingService's pending intent
		mPassivePendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(
				LocationManager.KEY_LOCATION_CHANGED),
				0);
		mPassivePendingIntent.cancel();
		mLocationManager.removeUpdates(mPassivePendingIntent);

		// Remove updates & cancel from LocationSaveIntentService pending intent
		Intent i = new Intent(getApplicationContext(),
				LocationReceiver.class);
		i.setAction(LocationManager.KEY_LOCATION_CHANGED);
		PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, 0);
		pi.cancel();
		mLocationManager.removeUpdates(pi);
		
		// Remove updates & cancel from LocationRequestIntentService pending intent
		i = new Intent(LocationManager.KEY_LOCATION_CHANGED);
		pi = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_CANCEL_CURRENT);
		pi.cancel();
		mLocationManager.removeUpdates(pi);
		
		// Remove update from the Tracking Service listener
		mLocationManager.removeUpdates(this);
		
		mLocationManager = null;
	}

	/**
	 * Called at the end of a tour
	 * Kills the alarm and then stops tracking.
	 */
	private void stopTracking() {
		Log.d(TAG, "STOP TRACKING RECEIVED");
		if( TrackingService.isBeta ){
			EndTrackingAlarmBeta.cancelAlarm(this);
		} else {
			EndTrackingAlarm.cancelAlarm(this);
		}
		stopAllTracking(); // stop all tracking and cancel all alarms
	}
	
	private void pauseTracking() {
		Log.d(TAG, "PAUSE TRACKING RECEIVED");
		stopAllTracking(); // stop all tracking but do not kill the end alarm..
	}
	
	private void updateNotifications() {
		if (isTracking) {
			// Hide pre-race notifications
//			NotificationHelper.hideRaceReminderNotification(this);
//			NotificationHelper.hideRaceOngoingNotification(this);

			// Show "problem" notifications (no location / network access).
			if (!Util.hasAnyLocationProvider(mLocationManager)) {
//				NotificationHelper.showNoLocationServicesNotification(this);
			}

			if (!Util.isNetworkConnected(this)) {
//				NotificationHelper.showNoNetworkNotification(this);
			}

			// Show ongoing tracking notification.
//			NotificationHelper.showServiceStartedNotification(this);
		} else {
//			NotificationHelper.hideAllNotifications(this);
		}
	}

	private void registerReceivers() {
		// Request location updates
		mRequestReceiver = new LocationRequestAlarm();
		IntentFilter requestIntentFilter = new IntentFilter(
				LocationRequestAlarm.REQUEST_LOCATION_UPDATE_ACTION);
		registerReceiver(mRequestReceiver, requestIntentFilter);

		// Receive location updates.
		mLocationReceiver = new LocationReceiver();
		IntentFilter locIntentFilter = new IntentFilter(
				LocationManager.KEY_LOCATION_CHANGED);
		registerReceiver(mLocationReceiver, locIntentFilter);

		// Manage battery
		mBatteryWatchDogReceiver = new LowBatteryReceiver();
		IntentFilter batteryIntentFilter = new IntentFilter();
		batteryIntentFilter.addAction(Intent.ACTION_BATTERY_LOW);
		registerReceiver(mBatteryWatchDogReceiver, batteryIntentFilter);
	}

	private void unregisterReceivers() {
		unregisterReceiver(mRequestReceiver);
		unregisterReceiver(mLocationReceiver);
		unregisterReceiver(mBatteryWatchDogReceiver);
	}
	
	private static void aquireWakeLock(Context c) {
		if (mWakeLock == null) {
			PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
			mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
			mWakeLock.acquire();
		}
	}
	
	private static void releaseWakeLock(Context c) {
		if (mWakeLock != null) {
			mWakeLock.release();
			mWakeLock = null;
		}
	}

	/**
	 * Show a notification if both network and gps providers are disabled.
	 */
	@Override
	public void onProviderDisabled(String provider) {
		if (!Util.hasAnyLocationProvider(mLocationManager)) {
//			NotificationHelper.showNoLocationServicesNotification(this);
		}
	}

	@Override
	public void onProviderEnabled(String provider) {
//		NotificationHelper.hideNoLocationServicesNotification(this);
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		// NOT USED
	}

	@Override
	public void onLocationChanged(Location location) {
		// NOT USED
//		mStateBroadcaster.locationUpdate(location);
	}
	

	public static void startTracking(Context c, boolean isBeta) {
		aquireWakeLock(c);
		TrackingService.isBeta = isBeta; // set the flag if it is beta or not.
		Intent i = new Intent(c, TrackingService.class);
		i.setAction(TrackingService.ACTION_START_TRACKING);
		c.startService(i);
	}

	public static void pauseTracking(Context c) {
		aquireWakeLock(c);
		Intent i = new Intent(c, TrackingService.class);
		i.setAction(TrackingService.ACTION_PAUSE_TRACKING);
		c.startService(i);
	}
	
	public static boolean isTracking() {
		return isTracking;
	}
}
