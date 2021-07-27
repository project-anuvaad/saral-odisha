package com.odisha_saraldata.scanner;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import com.facebook.react.ReactActivity;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.ReactContext;
import com.odisha_saraldata.R;
import com.odisha_saraldata.hwmodel.HWClassifier;
import com.odisha_saraldata.hwmodel.PredictionListener;
import com.odisha_saraldata.opencv.BlurDetection;
import com.odisha_saraldata.opencv.DetectShaded;
import com.odisha_saraldata.opencv.ExtractROIs;
import com.odisha_saraldata.opencv.TableCornerCirclesDetection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class OdishaPATScannerActivity extends ReactActivity implements CameraBridgeViewBase.CvCameraViewListener2 {
    private static final String  TAG                    = "Odisha_Saral::OdishaPAT";
    private static long mframeCount                     = 0;
    private static long mIgnoreFrameCount               = 0;
    private static final int START_PROCESSING_COUNT     = 20;

    private String mScannerType                            = SCANNER_TYPE.PAT;
    private boolean isHWClassiferAvailable              = true;
    private boolean isRelevantFrameAvailable            = false;
    private boolean mIsScanningComplete                 = false;
    private boolean mScanningResultShared               = false;

    private Mat                             mRgba;
    private CameraBridgeViewBase            mOpenCvCameraView;
    private TableCornerCirclesDetection     mTableCornerDetection;
    private ExtractROIs                     mROIs;
    private DetectShaded                    mDetectShaded;
    private BlurDetection                   blurDetection;
    private long                            mStartTime;
    private long                            mStartPredictTime;

    private int     mTotalClassifiedCount               = 0;
    private boolean mIsClassifierRequestSubmitted       = false;
    private HashMap<String, String> mPredictedDigits    = new HashMap<>();

    private HWClassifier hwClassifier;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };
    public OdishaPATScannerActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);
//        Bundle b = getIntent().getExtras();

        if(getIntent().hasExtra("scannerType")) {
            mScannerType = getIntent().getStringExtra("scannerType");
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_up_pat_scanner);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.up_pat_scanner_activity_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.setMaxFrameSize(1280,720);
        mOpenCvCameraView.enableFpsMeter();
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);

            /**
             * Now load the classifier
             */
            try {
                hwClassifier    = new HWClassifier(OdishaPATScannerActivity.this, new PredictionListener() {
                    @Override
                    public void OnPredictionSuccess(int digit, String id) {
                        Log.d(TAG, "predicted digit:" + digit + " unique id:" + id);
                        mTotalClassifiedCount++;
                        if(digit == 10) {
                            mPredictedDigits.put(id, "");
                        }
                        else {
                            mPredictedDigits.put(id, new Integer(digit).toString());
                        }

                        if (mIsClassifierRequestSubmitted && mTotalClassifiedCount >= mPredictedDigits.size()) {
                            mIsScanningComplete     = true;
                        }

                        if (mIsScanningComplete) {
                            Log.d(TAG, "Scaning completed, classification count " + mTotalClassifiedCount);
                            processScanningCompleted();
                        }
                    }

                    @Override
                    public void OnPredictionFailed(String error) {
                        Log.e(TAG, "Model prediction failed");
                        isHWClassiferAvailable  = false;
                    }

                    @Override
                    public void OnModelLoadStatus(String message) {
                        Log.d(TAG, "Model load status: " + message);
                        isHWClassiferAvailable  = true;
                    }
                });

                hwClassifier.initialize();

            } catch (IOException e) {
                Log.e(TAG, "Failed to load HWClassifier", e);
            }
        }
    }

    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public List<? extends CameraBridgeViewBase> getCameraViewList() {
        return null;
    }

    public void onCameraViewStarted(int width, int height) {
        mRgba                           = new Mat(height, width, CvType.CV_8UC4);
        mTableCornerDetection           = new TableCornerCirclesDetection(false);
        mROIs                           = new ExtractROIs(false);
        mDetectShaded                   = new DetectShaded(false);
        blurDetection                   = new BlurDetection(false);
        mTotalClassifiedCount           = 0;
        mIsScanningComplete             = false;
        mScanningResultShared           = false;
        isHWClassiferAvailable          = true;
        isRelevantFrameAvailable        = false;
        mIsClassifierRequestSubmitted   = false;
        mframeCount                     = 0;
        mIgnoreFrameCount               = 0;
    }

    public void onCameraViewStopped() {
        mRgba.release();
    }

    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        mRgba               = inputFrame.rgba();
        if (!isRelevantFrameAvailable) {
            processCameraFrame(mRgba, mframeCount);
            mframeCount ++;
        } else {
            showProcessingInformation(mRgba);
        }
        return mRgba;
    }

    private void processCameraFrame(Mat image, long frameCount) {
        double DARKNESS_THRESHOLD   = 80.0;
        Mat tableMat                = mTableCornerDetection.processMat(image);
        mStartTime                  = SystemClock.uptimeMillis();
//        return;

        if (tableMat != null && isHWClassiferAvailable) {
            if (mIgnoreFrameCount < START_PROCESSING_COUNT) {
                mIgnoreFrameCount ++;
                return;
            }
            if(blurDetection.detectBlur(tableMat)) {
                Log.d(TAG, "processCameraFrame: blurDetection after:: "+blurDetection.detectBlur(tableMat));
                return;
            }
            
            isRelevantFrameAvailable        = true;
            mIsScanningComplete             = false;
            mIsClassifierRequestSubmitted   = false;

            JSONArray rois              = getROIs();
            Log.d(TAG, "Received Table image, extracting: " + rois.length() + " ROIs:");

            mStartPredictTime       = SystemClock.uptimeMillis();
            MediaActionSound sound  = new MediaActionSound();
            sound.play(MediaActionSound.FOCUS_COMPLETE);

            try {
                for (int i = 0; i < rois.length(); i++) {
                    JSONObject roi  = rois.getJSONObject(i);

                    if (roi.getString("method").equals("classify")) {
                        StringBuilder sb    = new StringBuilder().append(roi.getInt("row")).append("_").append(roi.getInt("col")).append("_").append(roi.getInt("index"));
                        mPredictedDigits.put(sb.toString(), "0");

                        Mat digitROI        = mDetectShaded.getROIMat(tableMat, roi.getInt("top"), roi.getInt("left"), roi.getInt("bottom"), roi.getInt("right"));
                        if(hwClassifier != null) {
                            Log.d(TAG, "Requesting prediction for: " + sb.toString());
                            hwClassifier.classifyMat(digitROI, sb.toString());
                        }
                    }
                }
                mIsClassifierRequestSubmitted = true;
                Log.d(TAG, "classifier count: " + mPredictedDigits.size());

            } catch (JSONException e) {
                Log.e(TAG, "got JSON exception");
            }
        }
    }

    private JSONArray getROIs() {
        return mROIs.getOdishaPatROIs();
    }

    private void processScanningCompleted() {
        if (mScanningResultShared){
            return;
        }
        mScanningResultShared   = true;

        MediaActionSound sound  = new MediaActionSound();
        sound.play(MediaActionSound.SHUTTER_CLICK);

        JSONObject  response        = getScanResult();
        Log.d(TAG, "Scanning completed classifier count: " + mPredictedDigits.size());

        ReactInstanceManager mReactInstanceManager  = getReactNativeHost().getReactInstanceManager();
        ReactContext reactContext                   = mReactInstanceManager.getCurrentReactContext();
        Intent sendData                             = new Intent(reactContext, OdishaPATScannerActivity.class);

        sendData.putExtra("fileName", response.toString());
        mReactInstanceManager.onActivityResult(null, 1, 2, sendData);
        finish();
    }

    private JSONObject getScanResult() {
        JSONObject result       = new JSONObject();
        try {
            Log.d(TAG, "mPredictedDigits: " + new JSONObject(mPredictedDigits).toString());
            String studentRoll     = getStudentRoll();
            JSONArray marksData = getMarksData();
            String marksObtained = getMarksObtained();

            result.put("scannerType", mScannerType);
            result.put("studentRoll", studentRoll);
            result.put("marksData", marksData);
            result.put("marksObtained", marksObtained);
            result.put("predict", new Double((SystemClock.uptimeMillis() - mStartPredictTime)/1000));
            result.put("total", new Double((SystemClock.uptimeMillis() - mStartTime)/1000));

        } catch (JSONException e) {
            return result;
        }
        return result;
    }

    private String getStudentRoll() {
        StringBuffer sb = new StringBuffer();
        int cols = 16;
        int row = -1;
        try {
            for(int col = 0; col<cols; col++) {
                String key = row + "_" + col + "_" + col;
                String result = mPredictedDigits.get(key);
                if (result != null) {
                    sb.append(result);
                }
            }
        } catch (Exception e) {
            return sb.toString();
        }
        return sb.toString();
    }

    private String getMarksObtained() {
        StringBuffer sb = new StringBuffer();
        int cols = 3;
        int row = -2;
        try {
            for(int col = 0; col<cols; col++) {
                String key = row + "_" + col + "_" + col;
                String result = mPredictedDigits.get(key);
                if (result != null) {
                    sb.append(result);
                }
            }
        } catch (Exception e) {
            return sb.toString();
        }
        return sb.toString();
    }

    private JSONArray getMarksData() {
        int rows    = 10;
        int cols    = 2;

        JSONArray marks  = new JSONArray();
        JSONArray rois   = getROIs();
        try {
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    String key1     = row + "_" + col  + "_" + 0;
                    String result1  = mPredictedDigits.get(key1);
                    String key2     = row + "_" + col  + "_" + 1;
                    String result2  = mPredictedDigits.get(key2);

                    if (result1 != null && result2 != null) {
                        JSONObject mark  = new JSONObject();
//                        mark.put("row", row);

                        for (int i = 0; i < rois.length(); i++) {
                            JSONObject roi = rois.getJSONObject(i);
                            if (roi.getInt("row") == row && roi.getInt("col") == col) {
                                mark.put("question", roi.getInt("question"));
                                break;
                            }
                        }

                        mark.put("mark", result1 + result2);
                        mark.put("markErr", false);
                        marks.put(mark);
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Unable to collect students marks");
            return marks;
        }
        return marks;
    }

    private void showProcessingInformation(Mat image) {
        String text     = "Please wait, scanning is in progress !!";
        Point position  = new Point(image.width()/5, image.height() / 2);
        Scalar color    = new Scalar(0, 0, 255);
        int font        = Imgproc.COLOR_BGR2GRAY;
        int scale       = 1;
        int thickness   = 3;
        Imgproc.putText(image, text, position, font, scale, color, thickness);
    }

}
