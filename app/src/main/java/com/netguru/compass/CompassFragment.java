package com.netguru.compass;


import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;


/**
 * A simple {@link Fragment} subclass.
 */
public class CompassFragment extends Fragment implements
        OrientationToTargetLocation.ChangeEventListener {

    private static final String TAG = "CompassFragment";

    private static final String PREF_LAT = "pref_lat";
    private static final String PREF_LONG = "pref_long";

    private View mContent;
    private Button mCoordinatesBtn;
    private ImageView mCompassImg;
    private TextView mDistanceTxt;
    private TextView longTxt;
    private TextView latTxt;

    private SharedPreferences mPrefs;
    private float mLatitude = Float.NaN;
    private float mLongitude = Float.NaN;

    private double currentDegree;

    private OrientationToTargetLocation mOrientationProvider;

    public CompassFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onOrientationChanged(double bearing, double distance) {
        animation((float) bearing);
        if (Double.isNaN(distance))
            mDistanceTxt.setVisibility(View.INVISIBLE);
        else {
            mDistanceTxt.setText(getActivity().getString(R.string.distance) + ": " +
                    OrientationToTargetLocation.getRelativeDistance(distance));
            mDistanceTxt.setVisibility(View.VISIBLE);
        }
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment

        mOrientationProvider = new OrientationToTargetLocation(getActivity());
        mOrientationProvider.setChangeEventListener(this);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(getActivity());

        mContent = inflater.inflate(R.layout.fragment_compass, container, false);
        mCoordinatesBtn = (Button) mContent.findViewById(R.id.coordinates_btn);
        mCompassImg = (ImageView) mContent.findViewById(R.id.compass_img);
        mDistanceTxt = (TextView) mContent.findViewById(R.id.distance_txt);
        longTxt = (TextView) mContent.findViewById(R.id.long_txt);
        latTxt = (TextView) mContent.findViewById(R.id.lat_txt);

        mDistanceTxt.setVisibility(View.INVISIBLE);

        mCoordinatesBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createAlertDialog();
            }
        });

        return mContent;
    }


    private void createAlertDialog() {

        final View view = LayoutInflater.from(getActivity()).inflate(R.layout.coordinates_dialog,
                null, false);

        final AlertDialog dialog = new AlertDialog.Builder(getActivity()).setView(view)
                .setPositiveButton(getActivity().getString(R.string.ok), null).create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {


            @Override
            public void onShow(DialogInterface d) {

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View
                        .OnClickListener() {


                    @Override
                    public void onClick(View v) {
                        EditText longEdit = ((EditText) view.findViewById(R.id.longEdit));
                        EditText latEdit = ((EditText) view.findViewById(R.id.latEdit));

                        try {
                            mLongitude = parseInput(longEdit.getText()
                                    .toString(), 180, -180);
                        } catch (NumberFormatException e) {
                            longEdit.setError(getActivity().getString(R.string.incorrect_long));
                            return;
                        }
                        try {
                            mLatitude = parseInput(latEdit.getText().toString(), 90, -90);
                        } catch (NumberFormatException e) {
                            longEdit.setError(getActivity().getString(R.string.incorrect_lat));
                            return;
                        }

                        setLatLongEdit();
                        startOrientationManager();
                        dialog.dismiss();
                    }
                });
            }
        });

        dialog.show();

    }


    private void setLatLongEdit() {
        latTxt.setText(getActivity().getString(R.string.latitude) + ": " +
                mLatitude);
        longTxt.setText(getActivity().getString(R.string.longitude) + ": " +
                mLongitude);
    }

    private float parseInput(String value, double max, double min) throws NumberFormatException {

        float tmp = Float.parseFloat(value);

        if (tmp < min || tmp > max)
            throw new NumberFormatException();

        return tmp;
    }


    @Override
    public void onResume() {
        super.onResume();
        loadCoordinatesFromPrefs();
        setLatLongEdit();

        if (!Float.isNaN(mLatitude) && !Float.isNaN(mLongitude)) {
            startOrientationManager();
        }
    }


    private void startOrientationManager() {
        mOrientationProvider.setTargetLocation(new TargetLocation(mLatitude, mLongitude));
        mOrientationProvider.start();
    }


    @Override
    public void onPause() {
        super.onPause();
        saveCoordinatesToPrefs();
        mOrientationProvider.stop();
    }

    public void saveCoordinatesToPrefs() {
        mPrefs.edit().putFloat(PREF_LAT, mLatitude).putFloat(PREF_LONG, mLongitude).apply();
    }

    public void loadCoordinatesFromPrefs() {
        mLongitude = mPrefs.getFloat(PREF_LONG, Float.NaN);
        mLatitude = mPrefs.getFloat(PREF_LAT, Float.NaN);
    }


    private void animation(float degree) {
        RotateAnimation ra = new RotateAnimation(
                (float) currentDegree,
                -degree,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f);

        ra.setDuration(200);
        ra.setFillAfter(true);

        mCompassImg.startAnimation(ra);
        currentDegree = -degree;

    }
}
