package sonyxperiadev.extendedsettings;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.SystemProperties;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class LedDimmingService extends Service implements SensorEventListener {
    private static final String TAG = "LedDimming-Service";
    private static boolean isRunning = false;
    private static final String DUTY_PCTS_SCALING_FORMAT = "/sys/class/leds/led:rgb_%s/duty_pcts_scaling";
    private static final String[] COLORS = {"red", "green", "blue"};
    private static final Integer BRIGHT_SCALING = 100;
    private SensorManager sensorManager;
    private Sensor light;
    private Sensor proximity;
    private List<String> dutyPctsScalingPaths;

    // start in a no-op state
    private static Integer dimScaling = 100;
    private static Integer threshold = 0;
    private Integer currentScaling = BRIGHT_SCALING;
    private float lightValue = Integer.MAX_VALUE;
    private float proximityValue = Integer.MAX_VALUE;

    public static void updateThreshold(Integer newThreshold) {
        threshold = newThreshold;
    }

    public static void updateDimScaling(Integer newDimScaling) {
        if (newDimScaling > 2 && newDimScaling < 101) {
            dimScaling = newDimScaling;
        }
    }

    public static void updateThresholdAndScalingFromPreferences(Context context) {
        int newThreshold = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(ExtendedSettingsFragment.LED_DIM_THRESHOLD,0);
        updateThreshold(newThreshold);

        int newDimScaling = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(ExtendedSettingsFragment.LED_DIM_SCALING,50);
        updateDimScaling(newDimScaling);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateThresholdAndScalingFromPreferences(this);
        sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
        isRunning = true;
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        sensorManager = (SensorManager) getApplicationContext()
                .getSystemService(SENSOR_SERVICE);
        light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        dutyPctsScalingPaths = new ArrayList<>(COLORS.length);
        for (String color : COLORS) {
            String path = String.format(DUTY_PCTS_SCALING_FORMAT, color);
            dutyPctsScalingPaths.add(path);
        }
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        sensorManager.unregisterListener(this);
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // in case the service was stopped but somehow there were still events in the pipeline
        if (!isRunning) {
            return;
        }

        // if the event is for proximity, only update proximityValue and return
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            proximityValue = event.values[0];
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            lightValue = event.values[0];
        }
        Integer scaling = lightValue < threshold ? dimScaling : BRIGHT_SCALING;
        // no need to write to sysfs if the value remains the same
        if (scaling.equals(currentScaling)) {
            return;
        }

        // assume the sysfs writes are successful until an exception occcurs
        boolean scalingApplied = true;
        for (String dutyPctsScalingPath : dutyPctsScalingPaths) {
            try (FileWriter fileWriter = new FileWriter(dutyPctsScalingPath);
                 BufferedWriter writer = new BufferedWriter(fileWriter)) {
                writer.write(scaling);
            } catch (Exception e) {
                Log.e(TAG, String.format("Could not write to %s", dutyPctsScalingPath));
                scalingApplied = false;
                break;
            }
        }
        // set the written scaling as the current one only if all the writes succeeded
        if (scalingApplied) {
            currentScaling = scaling;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
