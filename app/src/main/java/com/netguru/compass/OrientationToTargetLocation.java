package com.netguru.compass;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Created by Mateusz on 2015-05-25.
 */
public class OrientationToTargetLocation implements SensorEventListener, LocationListener {
    public static final String TAG = "OrientationToLocation";

    /**
     * Interface definition for a callback to be invoked when the bearing changes.
     */
    public static interface ChangeEventListener {
        /**
         * Callback method to be invoked when the bearing changes.
         *
         * @param orientation the new bearing value
         */
        void onOrientationChanged(double orientation, double distance);
    }

    private final SensorManager mSensorManager;
    private final LocationManager mLocationManager;
    private final Sensor mSensorAccelerometer;
    private final Sensor mSensorMagneticField;

    // some arrays holding intermediate values read from the sensors, used to calculate our azimuth
    // value

    private float[] mValuesAccelerometer;
    private float[] mValuesMagneticField;
    private float[] mMatrixR;
    private float[] mMatrixI;
    private float[] mMatrixValues;



    private double mDistance = Double.NaN;

    private TargetLocation targetLocation;

    /**
     * minimum change of bearing (degrees) to notify the change listener
     */
    private final double mMinDiffForEvent;

    /**
     * minimum delay (millis) between notifications for the change listener
     */
    private final double mThrottleTime;

    /**
     * the change event listener
     */
    private ChangeEventListener mChangeEventListener;

    /**
     * angle to magnetic north
     */
    private AverageAngle mAzimuthRadians;

    /**
     * smoothed angle to magnetic north
     */
    private double mAzimuth = Double.NaN;

    /**
     * angle to true north
     */
    private double mOrientation = Double.NaN;

    /**
     * angle between current and target location
     */
    private double mAngleBetweenLocation = Double.NaN;

    /**
     * last notified angle to true north
     */
    private double mLastOrientation = Double.NaN;

    /**
     * Current GPS/WiFi location
     */
    private Location mLocation;

    /**
     * when we last dispatched the change event
     */
    private long mLastChangeDispatchedAt = -1;

    /**
     * Default constructor.
     *
     * @param context Application Context
     */
    public OrientationToTargetLocation(Context context) {
        this(context, 10, 0.5, 50);
    }

    /**
     * @param context         Application Context
     * @param smoothing       the number of measurements used to calculate a mean for the azimuth
     *                        . Set
     *                        this to 1 for the smallest delay. Setting it to 5-10 to prevents the
     *                        needle from going crazy
     * @param minDiffForEvent minimum change of orientation (degrees) to notify the change listener
     * @param throttleTime    minimum delay (millis) between notifications for the change listener
     */
    public OrientationToTargetLocation(Context context, int smoothing, double minDiffForEvent, int
            throttleTime) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        mSensorMagneticField = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        mValuesAccelerometer = new float[3];
        mValuesMagneticField = new float[3];

        mMatrixR = new float[9];
        mMatrixI = new float[9];
        mMatrixValues = new float[3];

        mMinDiffForEvent = minDiffForEvent;
        mThrottleTime = throttleTime;

        mAzimuthRadians = new AverageAngle(smoothing);
    }

    //==============================================================================================
    // Public API
    //==============================================================================================

    /**
     * Call this method to start orientation updates.
     */
    public void start() {
        mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, mSensorMagneticField, SensorManager.SENSOR_DELAY_UI);

        for (final String provider : mLocationManager.getProviders(true)) {
            if (LocationManager.GPS_PROVIDER.equals(provider)
                    || LocationManager.PASSIVE_PROVIDER.equals(provider)
                    || LocationManager.NETWORK_PROVIDER.equals(provider)) {
                if (mLocation == null) {
                    mLocation = mLocationManager.getLastKnownLocation(provider);
                }
                mLocationManager.requestLocationUpdates(provider, 0, 100.0f, this);
            }
        }
    }

    /**
     * call this method to stop orientation updates.
     */
    public void stop() {
        mSensorManager.unregisterListener(this, mSensorAccelerometer);
        mSensorManager.unregisterListener(this, mSensorMagneticField);
        mLocationManager.removeUpdates(this);
    }


    public void setTargetLocation(TargetLocation targetLocation) {
        this.targetLocation = targetLocation;
    }

    /**
     * @return current orientation
     */
    public double getOrientation() {
        return mOrientation;
    }

    /**
     * Returns the orientation event listener to which orientation events must be sent.
     *
     * @return the orientation event listener
     */
    public ChangeEventListener getChangeEventListener() {
        return mChangeEventListener;
    }

    /**
     * Specifies the orientation event listener to which orientation events must be sent.
     *
     * @param changeEventListener the orientation event listener
     */
    public void setChangeEventListener(ChangeEventListener changeEventListener) {
        this.mChangeEventListener = changeEventListener;
    }

    //==============================================================================================
    // SensorEventListener implementation
    //==============================================================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, mValuesAccelerometer, 0, 3);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, mValuesMagneticField, 0, 3);
                break;
        }

        boolean success = SensorManager.getRotationMatrix(mMatrixR, mMatrixI,
                mValuesAccelerometer,
                mValuesMagneticField);

        // calculate a new smoothed azimuth value and store to mAzimuth
        if (success) {
            SensorManager.getOrientation(mMatrixR, mMatrixValues);
            mAzimuthRadians.putValue(mMatrixValues[0]);
            mAzimuth = Math.toDegrees(mAzimuthRadians.getAverage());
        }

        // update mOrientation
        updateOrientation();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }

    //==============================================================================================
    // LocationListener implementation
    //==============================================================================================

    @Override
    public void onLocationChanged(Location location) {
        // set the new location
        this.mLocation = location;

        //this.mAngleBetweenLocation = getAngleBetweenLocations(mLocation, targetLocation);

        // update mOrientation


        updateOrientation();

        Log.e("angle", "angle: " + getAngleBetweenLocations(mLocation.getLatitude(), mLocation
                .getLongitude(), targetLocation.getLatitude(), targetLocation.getLongitude()));
    }


    private double getAngleBetweenLocations(double lat1, double lon1, double lat2, double lon2) {

        double longitude1 = lon1;
        double longitude2 = lon2;
        double latitude1 = Math.toRadians(lat1);
        double latitude2 = Math.toRadians(lat2);
        double longDiff = Math.toRadians(longitude2 - longitude1);
        double y = Math.sin(longDiff) * Math.cos(latitude2);
        double x = Math.cos(latitude1) * Math.sin(latitude2) - Math.sin(latitude1) * Math.cos
                (latitude2) * Math.cos(longDiff);

        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
    }

    @Override
    public void onProviderEnabled(String s) {
    }

    @Override
    public void onProviderDisabled(String s) {
    }

    //==============================================================================================
    // Private Utilities
    //==============================================================================================

    private void updateOrientation() {
        if (!Double.isNaN(this.mAzimuth)) {
            if (this.mLocation == null) {
                Log.w(TAG, "Location is NULL orientation is not true north!");
                mOrientation = mAzimuth;
            } else {
                mOrientation = getOrientationForLocation(this.mLocation);
                mDistance = getDistanceFromLatLonInKm(this.mLocation.getLatitude(), this
                        .mLocation.getLongitude(), this.targetLocation.getLatitude(), this
                        .targetLocation.getLongitude());
            }

            // Throttle dispatching based on mThrottleTime and minDiffForEvent
            if (System.currentTimeMillis() - mLastChangeDispatchedAt > mThrottleTime &&
                    (Double.isNaN(mLastOrientation) || Math.abs(mLastOrientation - mOrientation)
                            >= mMinDiffForEvent)) {
                mLastOrientation = mOrientation;
                if (mChangeEventListener != null) {
                    mChangeEventListener.onOrientationChanged(mOrientation, mDistance);
                }
                mLastChangeDispatchedAt = System.currentTimeMillis();
            }
        }
    }


    private double getDistanceFromLatLonInKm(double lat1, double long1, double lat2, double long2) {
        int R = 6371; // Radius of the earth in km
        double dLat = deg2rad(lat2 - lat1);  // deg2rad below
        double dLon = deg2rad(long2 - long1);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                        Math.cos(deg2rad(lat1)) * Math.cos(deg2rad(lat2)) *
                                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double d = R * c; // Distance in km
        return d;
    }

    private double deg2rad(double deg) {
        return deg * Math.PI / 180;
    }


    private double getOrientationForLocation(Location location) {
        double orientationToNorth = mAzimuth + getGeomagneticField(location).getDeclination();
        double bearing = getAngleBetweenLocations(mLocation.getLatitude(), mLocation.getLongitude
                (), targetLocation.getLatitude(), targetLocation.getLongitude());

        return orientationToNorth - bearing;
    }

    private GeomagneticField getGeomagneticField(Location location) {
        GeomagneticField geomagneticField = new GeomagneticField(
                (float) location.getLatitude(),
                (float) location.getLongitude(),
                (float) location.getAltitude(),
                System.currentTimeMillis());
        return geomagneticField;
    }


    public static String getRelativeDistance(double distance) {
        if (distance < 1) {
            distance *= 1000;
            return (((double) Math.round(distance * 100)) / 100) + "m";
        }
        return (((double) Math.round(distance * 100)) / 100) + "km";
    }

}

class TargetLocation {

    private double latitude;
    private double longitude;

    public TargetLocation(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}