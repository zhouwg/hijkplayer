package cdeos.media.encoder;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.Surface;

import cdeos.media.encoder.magicfilter.base.gpuimage.GPUImageFilter;
import cdeos.media.encoder.magicfilter.utils.MagicFilterFactory;
import cdeos.media.encoder.magicfilter.utils.MagicFilterType;
import cdeos.media.encoder.magicfilter.utils.OpenGLUtils;
import cdeos.media.player.CDELog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by Leo Ma
 */
public class SrsCameraView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private final static String TAG = SrsCameraView.class.getName();
    private GPUImageFilter magicFilter;
    private SurfaceTexture surfaceTexture;
    private int mOESTextureId = OpenGLUtils.NO_TEXTURE;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mPreviewWidth;
    private int mPreviewHeight;
    private volatile boolean mIsEncoding;
    private boolean mIsTorchOn = false;
    private float mInputAspectRatio;
    private float mOutputAspectRatio;
    private float[] mProjectionMatrix = new float[16];
    private float[] mSurfaceMatrix = new float[16];
    private float[] mTransformMatrix = new float[16];

    private Camera mCamera;
    private ByteBuffer mGLPreviewBuffer;
    private int mCamId = -1;
    private int mPreviewRotation = 90;
    private int mPreviewOrientation = Configuration.ORIENTATION_PORTRAIT;

    private Thread worker;
    private final Object writeLock = new Object();
    private ConcurrentLinkedQueue<IntBuffer> mGLIntBufferCache = new ConcurrentLinkedQueue<>();
    private PreviewCallback mPrevCb;
    private CameraCallbacksHandler cameraCallbacksHandler = new CameraCallbacksHandler();

    public SrsCameraView(Context context) {
        this(context, null);
    }

    public SrsCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GL10.GL_DITHER);
        GLES20.glClearColor(0, 0, 0, 0);

        magicFilter = new GPUImageFilter(MagicFilterType.NONE);
        magicFilter.init(getContext().getApplicationContext());
        magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);

        mOESTextureId = OpenGLUtils.getExternalOESTextureID();
        surfaceTexture = new SurfaceTexture(mOESTextureId);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                requestRender();
            }
        });

        // For camera preview on activity creation
        if (mCamera != null) {
            try {
                mCamera.setPreviewTexture(surfaceTexture);
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        magicFilter.onDisplaySizeChanged(width, height);
        magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);

        mOutputAspectRatio = width > height ? (float) width / height : (float) height / width;
        float aspectRatio = mOutputAspectRatio / mInputAspectRatio;
        if (width > height) {
            Matrix.orthoM(mProjectionMatrix, 0, -1.0f, 1.0f, -aspectRatio, aspectRatio, -1.0f, 1.0f);
        } else {
            Matrix.orthoM(mProjectionMatrix, 0, -aspectRatio, aspectRatio, -1.0f, 1.0f, -1.0f, 1.0f);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mSurfaceWidth != mPreviewWidth || mSurfaceHeight != mPreviewHeight) {
            //May be a buffer overflow in enableEncoding()
            //mPreviewWidth changed but onSurfaceCreated fired after enable encoding (mIsEncoding == true)
            //could be calling magicFilter.onInputSizeChanged(width, height) in setPreviewResolution() after changing mGLPreviewBuffer?
            //or start the encoder only after onSurfaceCreated ...            
            //Log.e(TAG, String.format("Surface dimensions differ from Preview. May be a buffer overflow. Surface: %dx%d, Preview: %dx%d ", mSurfaceWidth, mSurfaceHeight, mPreviewWidth, mPreviewHeight));
            return;
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        surfaceTexture.updateTexImage();

        surfaceTexture.getTransformMatrix(mSurfaceMatrix);
        Matrix.multiplyMM(mTransformMatrix, 0, mSurfaceMatrix, 0, mProjectionMatrix, 0);
        magicFilter.setTextureTransformMatrix(mTransformMatrix);
        magicFilter.onDrawFrame(mOESTextureId);

        if (mIsEncoding) {
            mGLIntBufferCache.add(magicFilter.getGLFboBuffer());
            synchronized (writeLock) {
                writeLock.notifyAll();
            }
        }
    }

    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;
    }

    public Camera getCamera() {
        return this.mCamera;
    }

    public void setPreviewCallback(Camera.PreviewCallback previewCallback) {
        this.mCamera.setPreviewCallback(previewCallback);
    }

    public int[] setPreviewResolution(int width, int height) {
        mCamera = openCamera();

        mPreviewWidth = width;
        mPreviewHeight = height;
        Camera.Size rs = adaptPreviewResolution(mCamera.new Size(width, height));
        if (rs != null) {
            mPreviewWidth = rs.width;
            mPreviewHeight = rs.height;
        }

        getHolder().setFixedSize(mPreviewWidth, mPreviewHeight);

        mCamera.getParameters().setPreviewSize(mPreviewWidth, mPreviewHeight);

        mGLPreviewBuffer = ByteBuffer.allocate(mPreviewWidth * mPreviewHeight * 4);
        mInputAspectRatio = mPreviewWidth > mPreviewHeight ?
                (float) mPreviewWidth / mPreviewHeight : (float) mPreviewHeight / mPreviewWidth;

        return new int[]{mPreviewWidth, mPreviewHeight};
    }


    public boolean setFilter(final MagicFilterType type) {
        if (mCamera == null) {
            return false;
        }

        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (magicFilter != null) {
                    magicFilter.destroy();
                }
                magicFilter = MagicFilterFactory.initFilters(type);
                if (magicFilter != null) {
                    magicFilter.init(getContext().getApplicationContext());
                    magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);
                    magicFilter.onDisplaySizeChanged(mSurfaceWidth, mSurfaceHeight);
                }
            }
        });
        requestRender();

        return true;
    }


    private void deleteTextures() {
        if (mOESTextureId != OpenGLUtils.NO_TEXTURE) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    GLES20.glDeleteTextures(1, new int[]{mOESTextureId}, 0);
                    mOESTextureId = OpenGLUtils.NO_TEXTURE;
                }
            });
        }
    }

    public void setCameraId(int id) {
        stopTorch();
        mCamId = id;
        setPreviewOrientation(mPreviewOrientation);
    }

    protected int getRotateDeg() {
        try {
            int rotate = ((Activity) getContext()).getWindowManager().getDefaultDisplay().getRotation();
            switch (rotate) {
                case Surface.ROTATION_0:
                    return 0;
                case Surface.ROTATION_90:
                    return 90;
                case Surface.ROTATION_180:
                    return 180;
                case Surface.ROTATION_270:
                    return 270;
            }
        } catch (Exception e) {
            e.printStackTrace();
            CDELog.j(TAG, "error: " + e.toString());
        }

        return -1;
    }

    public void setPreviewOrientation(int orientation) {
        mPreviewOrientation = orientation;
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCamId, info);

        int rotateDeg = getRotateDeg();

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mPreviewRotation = info.orientation % 360;
                mPreviewRotation = (360 - mPreviewRotation) % 360;  // compensate the mirror
            } else {
                mPreviewRotation = (info.orientation + 360) % 360;
            }
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mPreviewRotation = (info.orientation - 90) % 360;
                mPreviewRotation = (360 - mPreviewRotation) % 360;  // compensate the mirror
            } else {
                mPreviewRotation = (info.orientation + 90) % 360;
            }
        }

        if (rotateDeg > 0) {
            mPreviewRotation = mPreviewRotation % rotateDeg;
        }

        CDELog.j(TAG, "preview rotation :" + mPreviewRotation);
    }

    public int getCameraId() {
        return mCamId;
    }

    public void enableEncoding() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    while (!mGLIntBufferCache.isEmpty()) {
                        IntBuffer picture = mGLIntBufferCache.poll();
                        mGLPreviewBuffer.asIntBuffer().put(picture.array());
                        mPrevCb.onGetRgbaFrame(mGLPreviewBuffer.array(), mPreviewWidth, mPreviewHeight);
                    }
                    // Waiting for next frame
                    synchronized (writeLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500);
                        } catch (InterruptedException ie) {
                            worker.interrupt();
                        }
                    }
                }
            }
        });
        worker.start();
        mIsEncoding = true;
    }

    public void disableEncoding() {
        mIsEncoding = false;
        mGLIntBufferCache.clear();
        mGLPreviewBuffer.clear();

        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            worker = null;
        }
    }

    public boolean startCamera() {
        if (mCamera == null) {
            mCamera = openCamera();
            if (mCamera == null) {
                return false;
            }
        }

        Camera.Parameters params = mCamera.getParameters();
        //params.setPictureSize(mPreviewWidth, mPreviewHeight);
        params.setPreviewSize(mPreviewWidth, mPreviewHeight);
        int[] range = adaptFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);
        params.setPreviewFormat(ImageFormat.NV21);
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
        params.setRecordingHint(true);

        List<String> supportedFocusModes = params.getSupportedFocusModes();
        if (supportedFocusModes != null && !supportedFocusModes.isEmpty()) {
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                mCamera.autoFocus(null);
            } else {
                params.setFocusMode(supportedFocusModes.get(0));
            }
        }

        List<String> supportedFlashModes = params.getSupportedFlashModes();
        if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                if (mIsTorchOn) {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                }
            } else {
                params.setFlashMode(supportedFlashModes.get(0));
            }
        }

        cameraCallbacksHandler.onCameraParameters(params);
        mCamera.setParameters(params);

        mCamera.setDisplayOrientation(mPreviewRotation);

        try {
            mCamera.setPreviewTexture(surfaceTexture);
            //mCamera.setPreviewDisplay(holder);
        } catch (IOException e) {
            e.printStackTrace();
            CDELog.j(TAG, "error " + e.toString());
        }
        mCamera.startPreview();

        return true;
    }

    public void stopCamera() {
        disableEncoding();

        stopTorch();
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    protected Camera openCamera() {
/*
ISO(感光度): CMOS（或胶卷）对光线的敏感程度，用ISO100的胶卷，相机2秒可以正确曝光的话，同样光线条件下用ISO200的胶卷只需要1秒即可，用ISO400则只要0.5秒。
曝光时间:曝光时间是为了将光投射到相机感光片上，相机快门所要打开至关闭的时间
光圈:用来控制光线透过镜头，进入相机内感光面光量的装置
焦距:指的是平行的光线穿过镜片后，所汇集的焦点至镜片间之距离。数值越小，代表可以拍摄的角度越广，数值越大，代表可以拍摄的角度越小
景深:拍摄时，当镜头聚集于某个被摄体时，这个被摄体就能在相机上结成清晰影像。使被摄体产生较为清晰影像的纵深的范围叫景深
测光:测光模式：中央平均测光(average metering)、中央局部测光、点测光(spot metering)、多点测光、评价测光
自动曝光(Auto Exposure):相机根据光线条件自动来调整曝光时间等来确定曝光量
对焦: 对焦模式：自动对焦 AE(Auto Focus)、手动对焦 MF(Manual Focus) 自动对焦分为对比度对焦(contrast)、相位对焦(PDAF: Phase Detection Auto Focus)和混合对焦(hybrid)
闪光灯(Flashlight):通过闪光灯打闪照亮物体来达到拍出清晰图片的目的
ScreenFlash:通过屏幕打闪，照亮周围物体，拍出高清图片
高动态范围图像(HDR):HDR全称是High-Dynamic Range，即高动态范围图像技术。在拍照过程中开启HDR，可以让原先的暗场景变得更明亮更通透。
零延时拍照(ZSD):为了减少拍照延时,让拍照&回显瞬间完成的一种技术
连拍(ContinuousShot):通过节约数据传输时间来捕捉摄影时机
预览大小(PreviewSize):相机预览图片的大小
拍照大小(PictureSize):拍照生成图片的大小
自动白平衡(Auto white balance):AWB(Auto white balance)，自动白平衡是相机的默认设置，相机中有一结构复杂的矩形图，它可决定画面中的白平衡基准点，以此来达到白平衡调校
对比度:图像最亮和最暗之间的区域之间的比率，比值越大，从黑到白的渐变层次就越多，从而色彩表现越丰富
饱和度:指色彩的鲜艳程度
锐度:是反映图像平面清晰度和图像边缘锐利程度的一个指标
*/
        Camera camera = null;
        int frontCamId = -1;
        int backCamId = -1;
        CDELog.j(TAG, "open camera");
        if (mCamId < 0) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int numCameras = Camera.getNumberOfCameras();

            CDELog.j(TAG, "numCameras: " + numCameras);
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    backCamId = i;
                } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontCamId = i;
                    break;
                }
            }
            if (frontCamId != -1) {
                mCamId = frontCamId;
                CDELog.j(TAG, "using front cam");
            } else if (backCamId != -1) {
                mCamId = backCamId;
                CDELog.j(TAG, "using back cam");
            } else {
                mCamId = 0;
            }
        }

        if (backCamId != -1) {
            mCamId = backCamId; //强制使用后置摄像头
            CDELog.j(TAG, "using back cam");
        }
        try {
            camera = Camera.open(mCamId);

            camera.setErrorCallback(new Camera.ErrorCallback() {
                @Override
                public void onError(int error, Camera camera) {
                    //may be Camera.CAMERA_ERROR_EVICTED - Camera was disconnected due to use by higher priority user
                    stopCamera();
                    CDELog.j(TAG, "error : " + error);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            CDELog.j(TAG, "error : " + e.toString());
        }

        return camera;
    }

    private Camera.Size adaptPreviewResolution(Camera.Size resolution) {
        float diff = 100f;
        float xdy = (float) resolution.width / (float) resolution.height;
        Camera.Size best = null;
        for (Camera.Size size : mCamera.getParameters().getSupportedPreviewSizes()) {
            if (size.equals(resolution)) {
                return size;
            }
            float tmp = Math.abs(((float) size.width / (float) size.height) - xdy);
            if (tmp < diff) {
                diff = tmp;
                best = size;
            }
        }
        return best;
    }

    private int[] adaptFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    public boolean startTorch() {
        if (mCamera != null) {
            Camera.Parameters params = mCamera.getParameters();
            List<String> supportedFlashModes = params.getSupportedFlashModes();
            if (supportedFlashModes != null && !supportedFlashModes.isEmpty()) {
                if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                    mCamera.setParameters(params);
                    return true;
                }
            }
        }
        return false;
    }

    public void stopTorch() {
        if (mCamera != null) {
            try {
                Camera.Parameters params = mCamera.getParameters();
                params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                mCamera.setParameters(params);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public interface PreviewCallback {

        void onGetRgbaFrame(byte[] data, int width, int height);
    }

    static public class CameraCallbacksHandler implements CameraCallbacks {

        @Override
        public void onCameraParameters(Camera.Parameters params) {

        }
    }

    public interface CameraCallbacks {
        void onCameraParameters(Camera.Parameters params);
    }

    public void setCameraCallbacksHandler(CameraCallbacksHandler cameraCallbacksHandler) {
        this.cameraCallbacksHandler = cameraCallbacksHandler;
    }
}
