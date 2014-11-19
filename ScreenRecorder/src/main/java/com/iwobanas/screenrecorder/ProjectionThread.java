package com.iwobanas.screenrecorder;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import com.google.analytics.tracking.android.EasyTracker;
import com.iwobanas.screenrecorder.settings.AudioSource;
import com.iwobanas.screenrecorder.settings.Orientation;
import com.iwobanas.screenrecorder.settings.Resolution;
import com.iwobanas.screenrecorder.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class ProjectionThread implements Runnable {

    private static final String TAG = "scr_ProjectionThread";

    private final ProjectionThreadRunner runner;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private MediaCodec audioEncoder;
    private MediaCodec videoEncoder;
    private AudioRecord audioRecord;
    private MediaMuxer muxer;
    private boolean muxerStarted;
    private boolean startTimestampInitialized;
    private long startTimestampUs;
    private Context context;
    private Handler handler;
    private Thread audioRecordThread;

    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;


    private File outputFile;
    private String videoMime;
    private int videoWidth;
    private int videoHeight;
    private int videoBitrate;
    private int frameRate;
    private int sampleRate;
    private boolean hasAudio;
    private RecordingInfo recordingInfo;

    private volatile boolean stopped;
    private volatile boolean audioStopped;
    private volatile boolean asyncError;
    private volatile boolean destroyed;

    private VirtualDisplay.Callback displayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            super.onPaused();
            if (!stopped) {
                setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 515);
                asyncError = true;
            }
        }

        @Override
        public void onStopped() {
            super.onStopped();
            if (!stopped) {
                setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 514);
                asyncError = true;
            }
        }
    };

    public ProjectionThread(MediaProjection mediaProjection, Context context, ProjectionThreadRunner runner) {
        this.mediaProjection = mediaProjection;
        this.context = context.getApplicationContext();
        this.runner = runner;
        handler = new Handler();
    }

    public void startRecording(File outputFile) {
        this.outputFile = outputFile;
        recordingInfo = new RecordingInfo();
        recordingInfo.fileName = outputFile.getAbsolutePath();

        //TODO: report all caught exceptions to analytics

        Settings s = Settings.getInstance();

        switch (s.getVideoEncoder()) {
            case MediaRecorder.VideoEncoder.H264:
                videoMime = MediaFormat.MIMETYPE_VIDEO_AVC;
                break;
            case MediaRecorder.VideoEncoder.MPEG_4_SP:
                videoMime = MediaFormat.MIMETYPE_VIDEO_MPEG4;
                break;
            default:
                // use default encoder
                videoMime = MediaFormat.MIMETYPE_VIDEO_AVC;
        }
        Resolution resolution = s.getResolution();
        Orientation orientation = s.getOrientation() == Orientation.AUTO ? getOrientation() : s.getOrientation();
        if (orientation == Orientation.LANDSCAPE) {
            videoWidth = resolution.getVideoWidth();
            videoHeight = resolution.getVideoHeight();
            recordingInfo.verticalInput = 1;
            recordingInfo.rotateView = 1;
        } else {
            videoWidth = resolution.getVideoHeight();
            videoHeight = resolution.getVideoWidth();
            recordingInfo.verticalInput = 1;
            recordingInfo.rotateView = 0;
        }

        videoBitrate = s.getVideoBitrate().getBitrate();
        frameRate = s.getFrameRate();

        hasAudio = s.getAudioSource() != AudioSource.MUTE;
        sampleRate = s.getSamplingRate().getSamplingRate();

        new Thread(this).start();
    }

    private void setupVideoCodec() throws IOException {
        // Encoded video resolution matches virtual display.
        MediaFormat encoderFormat = MediaFormat.createVideoFormat(videoMime, videoWidth, videoHeight);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encoderFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        videoEncoder = MediaCodec.createEncoderByType(videoMime);
        videoEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = videoEncoder.createInputSurface();
        videoEncoder.start();
    }

    private void setupVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay("SCR Screen Recorder",
                videoWidth, videoHeight, DisplayMetrics.DENSITY_HIGH,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, displayCallback, handler);
    }

    private void setupAudioCodec() throws IOException {
        // Encoded video resolution matches virtual display.
        MediaFormat encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }


    private void setupAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                4 * minBufferSize);
    }

    private void startAudioRecord() {
        audioRecordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                try {
                    audioRecord.startRecording();
                } catch (Exception e) {
                    setError(RecordingProcessState.MICROPHONE_BUSY_ERROR, 506);
                    asyncError = true;
                    EasyTracker.getTracker().sendException("projection", e, false);
                    return;
                }
                try {
                    while (!audioStopped) {
                        int index = audioEncoder.dequeueInputBuffer(10000);
                        if (index < 0) {
                            continue;
                        }
                        ByteBuffer inputBuffer = audioEncoder.getInputBuffer(index);
                        if (inputBuffer == null) {
                            if (!stopped) {
                                setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 512);
                                asyncError = true;
                            }
                            return;
                        }
                        inputBuffer.clear();
                        int read = audioRecord.read(inputBuffer, inputBuffer.capacity());
                        if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                            break;
                        }
                        audioEncoder.queueInputBuffer(index, 0, read, getPresentationTimeUs(), 0);
                    }
                } catch (Exception e) {
                    if (!stopped) {
                        Log.e(TAG, "Audio error", e);
                        setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 511);
                        asyncError = true;
                        EasyTracker.getTracker().sendException("projection", e, false);
                    }
                } finally {
                    audioRecord.stop();
                    audioRecord.release();
                    audioRecord = null;
                }
            }
        });
        audioRecordThread.start();
    }

    private long getPresentationTimeUs() {
        if (!startTimestampInitialized) {
            startTimestampUs = System.nanoTime() / 1000;
            startTimestampInitialized = true;
        }
        return System.nanoTime() / 1000 - startTimestampUs;
    }

    private void startMuxerIfSetUp() {
        if ((!hasAudio || audioTrackIndex >= 0) && videoTrackIndex >= 0) {
            muxer.start();
            muxerStarted = true;
            setState(RecordingProcessState.RECORDING);
        }
    }

    @Override
    public void run() {

        int errorCodeHack = 504;

        try {

            try {
                setupVideoCodec();
            } catch (Exception e) {
                Log.e(TAG, "video error", e);
                setError(RecordingProcessState.VIDEO_CODEC_ERROR, 501);
                EasyTracker.getTracker().sendException("projection", e, false);
                return;
            }

            try {
                setupVirtualDisplay();
            } catch (SecurityException e) {
                try {
                    videoEncoder.stop();
                    videoEncoder.release();
                } catch (Exception ee) {
                    Log.w(TAG, "Error stopping video encoder", ee);
                }
                Intent intent = new Intent(context, MediaProjectionActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                EasyTracker.getTracker().sendException("projection", e, false);
                return;
            } catch (Exception e) {
                Log.e(TAG, "virtual display error", e);
                setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 513);
                EasyTracker.getTracker().sendException("projection", e, false);
                return;
            }

            if (hasAudio) {
                try {
                    setupAudioCodec();
                } catch (Exception e) {
                    Log.e(TAG, "audio error", e);
                    setError(RecordingProcessState.AUDIO_CONFIG_ERROR,  505);
                    EasyTracker.getTracker().sendException("projection", e, false);
                    return;
                }

                try {
                    setupAudioRecord();
                } catch (Exception e) {
                    Log.e(TAG, "AudioRecord error", e);
                    setError(RecordingProcessState.AUDIO_CONFIG_ERROR, 507);
                    EasyTracker.getTracker().sendException("projection", e, false);
                    return;
                }
            }

            try {
                muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (Exception e) {
                Log.e(TAG, "Muxer error", e);
                setError(RecordingProcessState.OUTPUT_FILE_ERROR, 201);
                EasyTracker.getTracker().sendException("projection", e, false);
                return;
            }

            if (hasAudio) {
                startAudioRecord();
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long lastAudioTimestampUs = -1;

            while (!stopped && !asyncError) {

                int encoderStatus;
                errorCodeHack = 504;

                if (hasAudio) {
                    errorCodeHack = 516;
                    encoderStatus = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                    //noinspection StatementWithEmptyBody
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no input frames... continue to video
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (audioTrackIndex > 0) {
                            setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 508);
                            break;
                        }
                        errorCodeHack = 517;
                        audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                        errorCodeHack = 518;
                        startMuxerIfSetUp();
                    } else if (encoderStatus < 0) {
                        Log.w(TAG, "unexpected result from audio encoder.dequeueOutputBuffer: " + encoderStatus);
                    } else {

                        errorCodeHack = 519;
                        ByteBuffer encodedData = audioEncoder.getOutputBuffer(encoderStatus);
                        if (encodedData == null) {
                            setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 509);
                            break;
                        }

                        if (bufferInfo.presentationTimeUs > lastAudioTimestampUs
                                && muxerStarted && bufferInfo.size != 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            lastAudioTimestampUs = bufferInfo.presentationTimeUs;
                            errorCodeHack = 520;
                            muxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo);
                        }

                        errorCodeHack = 521;
                        audioEncoder.releaseOutputBuffer(encoderStatus, false);

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 510);
                            break;
                        }
                    }
                }

                if (hasAudio && videoTrackIndex >= 0 && audioTrackIndex < 0)
                    continue; // wait for audio config before processing any video data frames

                errorCodeHack = 522;
                encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                //noinspection StatementWithEmptyBody
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no input frames... go back to audio
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (videoTrackIndex > 0) {
                        setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 502);
                        break;
                    }
                    errorCodeHack = 523;
                    videoTrackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                    errorCodeHack = 524;
                    startMuxerIfSetUp();
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else {

                    errorCodeHack = 525;
                    ByteBuffer encodedData = videoEncoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 503);
                        break;
                    }

                    if (bufferInfo.size != 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        //Bundle params = new Bundle(1);
                        //params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        //videoEncoder.setParameters(params);
                        bufferInfo.presentationTimeUs = getPresentationTimeUs();
                        //Log.v(TAG, "video " + bufferInfo.presentationTimeUs / 1000 + " " + bufferInfo.size + "    " + (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME));
                        errorCodeHack = 526;
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                    }

                    errorCodeHack = 527;
                    videoEncoder.releaseOutputBuffer(encoderStatus, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, 503);
                        break;
                    }
                }
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "Recording error", throwable);
            EasyTracker.getTracker().sendException("projection", throwable, false);
            setError(RecordingProcessState.UNKNOWN_RECORDING_ERROR, errorCodeHack);
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping muxer", e);
                    EasyTracker.getTracker().sendException("projection", e, false);
                }
                muxer = null;
            }


            if (virtualDisplay != null) {
                try {
                    virtualDisplay.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping display", e);
                    EasyTracker.getTracker().sendException("projection", e, false);
                }
                virtualDisplay = null;
            }
            if (surface != null) {
                try {
                    surface.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping surface", e);
                    EasyTracker.getTracker().sendException("projection", e, false);
                }
                surface = null;
            }
            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                    videoEncoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping video encoder", e);
                    EasyTracker.getTracker().sendException("projection", e, false);
                }
                videoEncoder = null;
            }

            if (audioRecordThread != null) {
                audioStopped = true;

                try {
                    audioRecordThread.join();
                } catch (InterruptedException ignore) {
                }
            }

            if (audioEncoder != null) {
                try {
                    audioEncoder.stop();
                    audioEncoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping audio encoder", e);
                    EasyTracker.getTracker().sendException("projection", e, false);
                }
                audioEncoder = null;
            }
        }

        if (!destroyed && recordingInfo.exitValue == -1) {
            setState(RecordingProcessState.FINISHED);
        }
    }

    public void stopRecording() {
        setState(RecordingProcessState.STOPPING);
        stopped = true;
    }

    public void destroy() {
        stopRecording();
        destroyed = true;
    }

    private void setState(RecordingProcessState state) {
        if (!destroyed) {
            runner.setState(state, recordingInfo);
        }
    }

    private void setError(RecordingProcessState state, int errorCode) {
        if (recordingInfo != null && recordingInfo.exitValue == -1) {
            recordingInfo.exitValue = errorCode;
            setState(state);
        }
    }

    public Orientation getOrientation() {
        Point s;
        try {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            s = new Point();
            display.getSize(s);
        } catch (Exception e) {
            Log.w(TAG, "Error retrieving screen orientation");
            EasyTracker.getTracker().sendException("projection", e, false);
            return Orientation.LANDSCAPE;
        }
        return s.x > s.y ? Orientation.LANDSCAPE : Orientation.PORTRAIT;
    }
}
