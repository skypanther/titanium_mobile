/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2012-2013 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package ti.modules.titanium.media;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollFunction;
import org.appcelerator.kroll.KrollObject;
import org.appcelerator.kroll.common.Log;
import org.appcelerator.titanium.TiApplication;
import org.appcelerator.titanium.TiBaseActivity;
import org.appcelerator.titanium.TiBlob;
import org.appcelerator.titanium.TiC;
import org.appcelerator.titanium.io.TiFile;
import org.appcelerator.titanium.io.TiFileFactory;
import org.appcelerator.titanium.proxy.TiViewProxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.hardware.Camera.Size;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;

public class TiCameraActivity extends TiBaseActivity implements SurfaceHolder.Callback
{
	private static final String TAG = "TiCameraActivity";
	private static Camera camera;
	private static Size optimalPreviewSize;
	private static List<Size> supportedPreviewSizes;
	private static int frontCameraId = Integer.MIN_VALUE; // cache
	private static int backCameraId = Integer.MIN_VALUE; //cache
	private TiViewProxy localOverlayProxy = null;
	private SurfaceView preview;
	private PreviewLayout previewLayout;
	private FrameLayout cameraLayout;
	private boolean previewRunning = false;
	private int currentRotation;

	public static TiViewProxy overlayProxy = null;
	public static TiCameraActivity cameraActivity = null;

	public static KrollObject callbackContext;
	public static KrollFunction successCallback, errorCallback, cancelCallback;
	public static boolean saveToPhotoGallery = false;
	public static int whichCamera = MediaModule.CAMERA_REAR;
	public static int cameraFlashMode = MediaModule.CAMERA_FLASH_OFF;
	public static boolean autohide = true;
	public static List<Size> supportedPictureSizes;
	public static Size desiredPictureSize;
	public static Size targetPictureSize; // will be >= desiredPictureSize

	private static class PreviewLayout extends FrameLayout
	{
		private double aspectRatio = 1;
		private Runnable runAfterMeasure;

		public PreviewLayout(Context context)
		{
			super(context);
		}

		protected void prepareNewPreview(Runnable runnable)
		{
			runAfterMeasure = runnable;

			this.post(new Runnable()
			{
				@Override
				public void run()
				{
					PreviewLayout.this.requestLayout();
					PreviewLayout.this.invalidate();
				}
			});
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
		{
			int previewWidth = MeasureSpec.getSize(widthMeasureSpec);
			int previewHeight = MeasureSpec.getSize(heightMeasureSpec);

			// Set the preview size to the most optimal given the target size
			optimalPreviewSize = getOptimalPreviewSize(supportedPreviewSizes, previewWidth, previewHeight);
			if (optimalPreviewSize != null) {
				if (previewWidth > previewHeight) {
					aspectRatio = (double) optimalPreviewSize.width / optimalPreviewSize.height;
				} else {
					aspectRatio = (double) optimalPreviewSize.height / optimalPreviewSize.width;
				}
			}
			if (previewHeight < previewWidth / aspectRatio) {
				previewHeight = (int) (previewWidth / aspectRatio + .5);

			} else {
				previewWidth = (int) (previewHeight * aspectRatio + .5);
			}
			
			super.onMeasure(MeasureSpec.makeMeasureSpec(previewWidth,
					MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(
					previewHeight, MeasureSpec.EXACTLY));

			if (runAfterMeasure != null) {
				final Runnable run = runAfterMeasure;
				runAfterMeasure = null;
				this.post(run);
			}
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		setFullscreen(true);
		
		super.onCreate(savedInstanceState);

		// create camera preview
		preview = new SurfaceView(this);
		SurfaceHolder previewHolder = preview.getHolder();
		previewHolder.addCallback(this);
		previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		// set preview overlay
		localOverlayProxy = overlayProxy;

		// set overall layout - will populate in onResume
		previewLayout = new PreviewLayout(this);
		cameraLayout = new FrameLayout(this);
		cameraLayout.setBackgroundColor(Color.BLACK);
		cameraLayout.addView(previewLayout, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
			LayoutParams.MATCH_PARENT, Gravity.CENTER));

		setContentView(cameraLayout);

	}

	public void surfaceChanged(SurfaceHolder previewHolder, int format, int width, int height)
	{
		startPreview(previewHolder);
	}

	public void surfaceCreated(SurfaceHolder previewHolder)
	{
		try {
			camera.setPreviewDisplay(previewHolder);
		} catch (Exception e) {
			onError(MediaModule.UNKNOWN_ERROR, "Unable to setup preview surface: " + e.getMessage());
			cancelCallback = null;
			finish();
			return;
		}
		currentRotation = getWindowManager().getDefaultDisplay().getRotation();
	}

	// make sure to call release() otherwise you will have to force kill the app before
	// the built in camera will open
	public void surfaceDestroyed(SurfaceHolder previewHolder)
	{
		stopPreview();
		if (camera != null) {
			camera.release();
			camera = null;
		}
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (camera == null) {

			if (whichCamera == MediaModule.CAMERA_FRONT) {
				openCamera(getFrontCameraId());

			} else {
				openCamera();
			}
		}
		if (camera != null) {
			setFlashMode(cameraFlashMode);
		}
		if (camera == null) {
			return; // openCamera will have logged error.
		}
		
		try {
			//This needs to be called to make sure action bar is gone
			if (android.os.Build.VERSION.SDK_INT < 11) {
				ActionBar actionBar = getSupportActionBar();
				if (actionBar != null) {
					actionBar.hide();
				}
			}
		} catch(Throwable t) {
			//Ignore this
		}
		
		cameraActivity = this;
		previewLayout.addView(preview, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		View overlayView = localOverlayProxy.getOrCreateView().getNativeView();
		ViewGroup parent = (ViewGroup) overlayView.getParent();
		// Detach from the parent if applicable
		if (parent != null) {
			parent.removeView(overlayView);
		}
		cameraLayout.addView(overlayView, new FrameLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
	}

	public static void setFlashMode(int cameraFlashMode)
	{
		TiCameraActivity.cameraFlashMode = cameraFlashMode;
		if (camera != null) {
			try {
				Parameters p = camera.getParameters();
				if (cameraFlashMode == MediaModule.CAMERA_FLASH_OFF) {
					p.setFlashMode(Parameters.FLASH_MODE_OFF);
				} else if (cameraFlashMode == MediaModule.CAMERA_FLASH_ON) {
					p.setFlashMode(Parameters.FLASH_MODE_ON);
				} else if (cameraFlashMode == MediaModule.CAMERA_FLASH_AUTO) {
					p.setFlashMode(Parameters.FLASH_MODE_AUTO);
				}
				camera.setParameters(p);
			} catch (Throwable t) {
				Log.e(TAG, "Could not set flash mode", t);
			}
		}
	}

	@Override
	protected void onPause(){
		super.onPause();

		stopPreview();
		previewLayout.removeView(preview);
		cameraLayout.removeView(localOverlayProxy.getOrCreateView().getNativeView());

		try {
			camera.release();
			camera = null;
		} catch (Throwable t) {
			Log.d(TAG, "Camera is not open, unable to release", Log.DEBUG_MODE);
		}

		cameraActivity = null;
	}

	private void startPreview(SurfaceHolder previewHolder)
	{
		if (camera == null) {
			return;
		}

		int rotation = getWindowManager().getDefaultDisplay().getRotation();

		if (currentRotation == rotation && previewRunning) {
			return;
		}		

		if (previewRunning) {
			try {
				camera.stopPreview();
			} catch (Exception e) {
				// ignore: tried to stop a non=existent preview
			}
		}
		
		//Set the proper display orientation
		int cameraId = Integer.MIN_VALUE;
		if (whichCamera == MediaModule.CAMERA_FRONT) {
			cameraId = TiCameraActivity.getFrontCameraId();
		} else {
			cameraId = TiCameraActivity.getBackCameraId();
		}
		CameraInfo info = new Camera.CameraInfo();
		Camera.getCameraInfo(cameraId, info);

		currentRotation = rotation;
		
		//Clockwise and anticlockwise
		int degrees = 0, degrees2 = 0;
		
		//Let Camera display in same orientation as display
		switch (currentRotation) {
			case Surface.ROTATION_0: degrees = degrees2 = 0; break;
			case Surface.ROTATION_180: degrees = degrees2 = 180; break;
			case Surface.ROTATION_90: {degrees = 90; degrees2 = 270; } break;
			case Surface.ROTATION_270: {degrees = 270; degrees2 = 90;} break;
		}
		
		int result, result2;
		if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
			result = (info.orientation + degrees) % 360;
			result = (360 - result) % 360;  // compensate the mirror
		} else {  // back-facing
			result = (info.orientation - degrees + 360) % 360;
		}
		
		//Set up Camera Rotation so jpegCallback has correctly rotated image
		Parameters param = camera.getParameters();
		if (info.facing == CameraInfo.CAMERA_FACING_FRONT) {
			result2 = (info.orientation - degrees2 + 360) % 360;
		} else {  // back-facing camera
			result2 = (info.orientation + degrees2) % 360;
		}
		
		camera.setDisplayOrientation(result);
		param.setRotation(result2);

		// Set appropriate focus mode if supported.
		List<String> supportedFocusModes = param.getSupportedFocusModes();
		if (supportedFocusModes.contains(MediaModule.FOCUS_MODE_CONTINUOUS_PICTURE)) {
			param.setFocusMode(MediaModule.FOCUS_MODE_CONTINUOUS_PICTURE);
		} else if (supportedFocusModes.contains(Parameters.FOCUS_MODE_AUTO)) {
			param.setFocusMode(Parameters.FOCUS_MODE_AUTO);
		} else if (supportedFocusModes.contains(Parameters.FOCUS_MODE_MACRO)) {
			param.setFocusMode(Parameters.FOCUS_MODE_MACRO);
		}

		if (optimalPreviewSize != null) {
			param.setPreviewSize(optimalPreviewSize.width, optimalPreviewSize.height);
			List<Size> pictSizes = param.getSupportedPictureSizes();
			Size pictureSize = getOptimalPictureSize(pictSizes);
			if (pictureSize != null) {
				param.setPictureSize(pictureSize.width, pictureSize.height);
			}
		}
		if (this.targetPictureSize != null) {
			param.setPictureSize(this.targetPictureSize.width, this.targetPictureSize.height);
		}
		camera.setParameters(param);

		try {
			camera.setPreviewDisplay(previewHolder);
			previewRunning = true;
			camera.startPreview();
		} catch (Exception e) {
			onError(MediaModule.UNKNOWN_ERROR, "Unable to setup preview surface: " + e.getMessage());
			finish();
			return;
		}
	}

	private void stopPreview()
	{
		if (camera == null || !previewRunning) {
			return;
		}
		camera.stopPreview();
		previewRunning = false;
	}

	@Override
	public void finish()
	{
		// For API 10 and above, the whole activity gets destroyed during an orientation change. We only want to set
		// overlayProxy to null when we call finish, i.e. when we hide the camera or take a picture. By doing this, the
		// overlay proxy will be available during the recreation of the activity during an orientation change.
		overlayProxy = null;
		super.finish();
	}

	/**
	 * Computes the optimal preview size given the target display size and aspect ratio.
	 * 
	 * @param supportPreviewSizes
	 *            a list of preview sizes the camera supports
	 * @param targetSize
	 *            the target display size that will render the preview
	 * @return the optimal size of the preview
	 */
	private static Size getOptimalPreviewSize(List<Size> sizes, int w, int h)
	{
		double targetRatio = 1;
		if (w > h) {
			targetRatio = (double) w / h;
		} else {
			targetRatio = (double) h / w;
		}
		if (sizes == null) {
			return null;
		}
		Size optimalSize = null;
		double minAspectDiff = Double.MAX_VALUE;

		// Try to find an size match aspect ratio and size
		for (Size size : sizes) {
			double ratio = (double) size.width / size.height;
			if (Math.abs(ratio - targetRatio) < minAspectDiff) {
				optimalSize = size;
				minAspectDiff = Math.abs(ratio - targetRatio);
			}
		}
		
		return optimalSize;
	}
	
	/**
	 * Computes the optimal picture size given the preview size. 
	 * This returns the maximum resolution size.
	 * 
	 * @param sizes
	 *            a list of picture sizes the camera supports
	 * @return the optimal size of the picture
	 */
	private static Size getOptimalPictureSize(List<Size> sizes)
	{
		if (sizes == null) {
			return null;
		}
		Size optimalSize = null;

		long resolution = 0;

		for (Size size : sizes) {
			if (size.width * size.height > resolution) {
				optimalSize = size;
				resolution = size.width * size.height;
			}
		}

		return optimalSize;
	}

	private static void onError(int code, String message)
	{
		if (errorCallback == null) {
			Log.e(TAG, message);
			return;
		}

		KrollDict dict = new KrollDict();
		dict.putCodeAndMessage(code, message);
		dict.put(TiC.PROPERTY_MESSAGE, message);

		errorCallback.callAsync(callbackContext, dict);
	}

	private static File writeToFile(byte[] data, boolean saveToGallery) throws Throwable
	{
		try
		{
			File imageFile = null;
			if (saveToGallery) {
				imageFile = MediaModule.createGalleryImageFile();
			} else {
				// Save the picture in the internal data directory so it is private to this application.
				imageFile = TiFileFactory.createDataFile("tia", ".jpg");
			}
			
			FileOutputStream imageOut = new FileOutputStream(imageFile);
			imageOut.write(data);
			imageOut.close();
			
			if (saveToGallery) {
				Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
				Uri contentUri = Uri.fromFile(imageFile);
				mediaScanIntent.setData(contentUri);
				Activity activity = TiApplication.getAppCurrentActivity();
				activity.sendBroadcast(mediaScanIntent);
			}
			return imageFile;
			
		} catch (Throwable t) {
			throw t;
		}
	}

	static public void takePicture()
	{
		String focusMode = camera.getParameters().getFocusMode();
		if (!(focusMode.equals(Parameters.FOCUS_MODE_EDOF) || focusMode.equals(Parameters.FOCUS_MODE_FIXED) || focusMode
			.equals(Parameters.FOCUS_MODE_INFINITY))) {
			AutoFocusCallback focusCallback = new AutoFocusCallback()
			{
				public void onAutoFocus(boolean success, Camera camera)
				{
					camera.takePicture(shutterCallback, null, jpegCallback);
					if (!success) {
						Log.w(TAG, "Unable to focus.");
					}
					camera.cancelAutoFocus();
				}
			};
			camera.autoFocus(focusCallback);
		} else {
			camera.takePicture(shutterCallback, null, jpegCallback);
		}
	}

	static public List getSupportedPictureSizes()
	{
		List<Size> pictSizes = camera.getParameters().getSupportedPictureSizes();
		this.supportedPictureSizes = pictSizes;
		return pictSizes;
	}

	static public List setDesiredPictureSize(int width, int height)
	{
		if(this.supportedPictureSizes == null) {
			this.getSupportedPictureSizes();
		}

		// sort sizes in ascending order
		Collections.sort(this.supportedPictureSizes, new Comparator<Camera.Size>() {
			public int compare(final Camera.Size a, final Camera.Size b) {
				return a.width * a.height - b.width * b.height;
			}
		});

		// loop through and grab the smallest supported size that is larger than the desired size
		Camera.Size theClosestSupportedSize = null;
		for (Camera.Size size : this.supportedPictureSizes) {
			if (size.width >= width && size.height >= height && theClosestSupportedSize == null) {
				theClosestSupportedSize=size;
			}
		}
		if(theClosestSupportedSize == null) {
			// desired was bigger than max size supported, to take the largest supported size
			theClosestSupportedSize = this.supportedPictureSizes.get(this.supportedPictureSizes.size() - 1);
		}

		camera.parameters.setPictureSize(theClosestSupportedSize.width, theClosestSupportedSize.height);
		this.targetSize = theClosestSupportedSize;
	}

	static public List getDesiredPictureSize()
	{
		return this.desiredPictureSize;
	}

	public boolean isPreviewRunning()
	{
		return this.previewRunning;
	}

	static public void hide()
	{
		cameraActivity.setResult(Activity.RESULT_OK);
		cameraActivity.finish();
	}

	static ShutterCallback shutterCallback = new ShutterCallback()
	{
		// Just the presence of a shutter callback will
		// allow the shutter click sound to occur (at least
		// on Jelly Bean on a stock Google phone, which
		// was remaining silent without this.)
		@Override
		public void onShutter()
		{
			// No-op
		}
	};

	static PictureCallback jpegCallback = new PictureCallback()
	{
		public void onPictureTaken(byte[] data, Camera camera)
		{
			try {
				File imageFile = writeToFile(data, saveToPhotoGallery);
				if (successCallback != null) {
					TiFile theFile = new TiFile(imageFile, imageFile.toURI().toURL().toExternalForm(), false);
					TiBlob theBlob = TiBlob.blobFromFile(theFile);
					KrollDict response = MediaModule.createDictForImage(theBlob, theBlob.getMimeType());
					successCallback.callAsync(callbackContext, response);
				}				
			} catch (Throwable t) {
				if (errorCallback != null) {
					KrollDict response = new KrollDict();
					response.putCodeAndMessage(MediaModule.UNKNOWN_ERROR, t.getMessage());
					errorCallback.callAsync(callbackContext, response);
				}
			}
			

			if (autohide) {
				cameraActivity.finish();
			} else {
				camera.startPreview();
			}
		}
	};

	private static int getFrontCameraId()
	{
		if (frontCameraId == Integer.MIN_VALUE) {
			int count = Camera.getNumberOfCameras();
			for (int i = 0; i < count; i++) {
				CameraInfo info = new CameraInfo();
				Camera.getCameraInfo(i, info);
				if (info.facing == CameraInfo.CAMERA_FACING_FRONT) {
					frontCameraId = i;
					break;
				}
			}
		}

		return frontCameraId;
	}
	
	private static int getBackCameraId()
	{
		if (backCameraId == Integer.MIN_VALUE) {
			int count = Camera.getNumberOfCameras();
			for (int i = 0; i < count; i++) {
				CameraInfo info = new CameraInfo();
				Camera.getCameraInfo(i, info);
				if (info.facing == CameraInfo.CAMERA_FACING_BACK) {
					backCameraId = i;
					break;
				}
			}
		}

		return backCameraId;
	}

	private void openCamera()
	{
		openCamera(Integer.MIN_VALUE);
	}

	private void openCamera(int cameraId)
	{
		if (previewRunning) {
			stopPreview();
		}

		if (camera != null) {
			camera.release();
			camera = null;
		}

		if (cameraId == Integer.MIN_VALUE) {
			camera = Camera.open();
		} else {
			camera = Camera.open(cameraId);
		}

		if (camera == null) {
			onError(MediaModule.UNKNOWN_ERROR, "Unable to access the camera.");
			finish();
			return;
		}

		supportedPreviewSizes = camera.getParameters()
				.getSupportedPreviewSizes();
		optimalPreviewSize = null; // Re-calc'd in PreviewLayout.onMeasure.
	}

	protected void switchCamera(int whichCamera)
	{
		boolean front = whichCamera == MediaModule.CAMERA_FRONT;
		int frontId = Integer.MIN_VALUE; // no-val

		if (front) {
			frontId = getFrontCameraId();
			if (frontId == Integer.MIN_VALUE) {
				Log.e(TAG,
						"switchCamera cancelled because this device has no front camera.");
				return;
			}
		}

		TiCameraActivity.whichCamera = whichCamera;

		if (front) {
			openCamera(frontId);
		} else {
			openCamera();
		}

		if (camera == null) {
			return;
		}

		// Force the onMeasure of PreviewLayout, so aspect ratio, best
		// dimensions, etc. can be setup for camera preview. And specify
		// a runnable that will be called after the previewLayout
		// measures. The runnable will start the camera preview.
		// This all guarantees us that the camera preview won't start until
		// after the layout has been measured.
		previewLayout.prepareNewPreview(new Runnable()
		{
			@Override
			public void run()
			{
				startPreview(preview.getHolder());
			}
		});

	}
	
	@Override
	public void onBackPressed()
	{
		if (cancelCallback != null) {
			KrollDict response = new KrollDict();
			response.putCodeAndMessage(-1, "User cancelled the request");
			cancelCallback.callAsync(callbackContext, response);
		}
		super.onBackPressed();
	}
	
	@Override
	public boolean onKeyDown (int keyCode, KeyEvent event) 
	{
		if (keyCode == KeyEvent.KEYCODE_MENU) {
			//Workaround for http://code.google.com/p/android/issues/detail?id=61394
			//Exists atleast till version 19.1 of support library
			return true;
		}
		
		return super.onKeyDown(keyCode, event);
	}
}
