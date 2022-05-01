/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.danny.framesSquencce;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * @author danny.jiang
 */

public class FrameSequenceDrawable extends Drawable implements Animatable, Runnable {
    private static final String TAG = "FrameSequenceDrawable";

    /**
     * These constants are chosen to imitate common browser behavior for WebP/GIF.
     * If other decoders are added, this behavior should be moved into the WebP/GIF decoders.
     * <p>
     * Note that 0 delay is undefined behavior in the GIF standard.
     */
    private static final long MIN_DELAY_MS = 100;
    private static final long DEFAULT_DELAY_MS = 100;

    private final Object sLock = new Object();
    private HandlerThread sDecodingThread;
    private Handler sDecodingThreadHandler;

    private void initializeDecodingThread() {
        synchronized (sLock) {
            if (sDecodingThread != null) return;

            sDecodingThread = new HandlerThread("FrameSequence decoding thread",
                    Process.THREAD_PRIORITY_BACKGROUND);
            sDecodingThread.start();
            sDecodingThreadHandler = new Handler(sDecodingThread.getLooper());
        }
    }

    public void setAnimationCount(int animationCount) {
        mFrameSequence.setDefaultLoopCount(animationCount);
    }

    public static interface OnAnimationListener {
        /**
         * Called when a FrameSequenceDrawable has finished looping.
         * <p>
         * Note that this is will not be called if the drawable is explicitly
         * stopped, or marked invisible.
         */
        public abstract void onFinished(FrameSequenceDrawable drawable);

        void onStart(FrameSequenceDrawable drawable);
    }

    public static interface BitmapProvider {
        /**
         * Called by FrameSequenceDrawable to aquire an 8888 Bitmap with minimum dimensions.
         */
        public abstract Bitmap acquireBitmap(int minWidth, int minHeight);

        /**
         * Called by FrameSequenceDrawable to release a Bitmap it no longer needs. The Bitmap
         * will no longer be used at all by the drawable, so it is safe to reuse elsewhere.
         * <p>
         * This method may be called by FrameSequenceDrawable on any thread.
         */
        public abstract void releaseBitmap(Bitmap bitmap);
    }

    public static BitmapProvider sAllocatingBitmapProvider = new BitmapProvider() {
        @Override
        public Bitmap acquireBitmap(int minWidth, int minHeight) {
            return Bitmap.createBitmap(minWidth, minHeight, Bitmap.Config.ARGB_8888);
        }

        @Override
        public void releaseBitmap(Bitmap bitmap) {
        }
    };

    /**
     * Register a callback to be invoked when a FrameSequenceDrawable finishes looping.
     *
     * @see #setLoopBehavior(int)
     */
    public void setOnAnimationListener(OnAnimationListener onAnimationListener) {
        mOnAnimationListener = onAnimationListener;
    }

    /**
     * Loop only once.
     */
    public static final int LOOP_ONCE = 1;

    /**
     * Loop continuously. The OnFinishedListener will never be called.
     */
    public static final int LOOP_INF = 2;

    /**
     * Use loop count stored in source data, or LOOP_ONCE if not present.
     */
    public static final int LOOP_DEFAULT = 3;

    /**
     * Define looping behavior of frame sequence.
     * <p>
     * Must be one of LOOP_ONCE, LOOP_INF, or LOOP_DEFAULT
     */
    public void setLoopBehavior(int loopBehavior) {
        mLoopBehavior = loopBehavior;
    }

    private final FrameSequence mFrameSequence;
    private final FrameSequence.State mFrameSequenceState;

    private final Paint mPaint;
    private final Rect mSrcRect;

    //Protects the fields below
    private final Object mLock = new Object();

    private final BitmapProvider mBitmapProvider;
    private boolean mDestroyed = false;
    private Bitmap mFrontBitmap;
    private Bitmap mBackBitmap;

    private static final int STATE_SCHEDULED = 1;
    private static final int STATE_DECODING = 2;
    private static final int STATE_WAITING_TO_SWAP = 3;
    private static final int STATE_READY_TO_SWAP = 4;

    private int mState;
    private int mLoopBehavior = LOOP_DEFAULT;

    private long mLastSwap;
    private long mNextSwap;
    private int mNextFrameToDecode;
    private OnAnimationListener mOnAnimationListener;

    /**
     * Runs on decoding thread, only modifies mBackBitmap's pixels
     */
    private Runnable mDecodeRunnable = new Runnable() {
        @Override
        public void run() {
            int nextFrame;
            Bitmap bitmap;
            synchronized (mLock) {
                if (mDestroyed) return;

                nextFrame = mNextFrameToDecode;
                if (nextFrame < 0) {
                    return;
                }
                bitmap = mBackBitmap;
                mState = STATE_DECODING;
            }
            int lastFrame = nextFrame - 2;
            long invalidateTimeMs = mFrameSequenceState.getFrame(nextFrame, bitmap, lastFrame);

            if (invalidateTimeMs < MIN_DELAY_MS) {
                invalidateTimeMs = DEFAULT_DELAY_MS;
            }

            boolean schedule = false;
            Bitmap bitmapToRelease = null;
            synchronized (mLock) {
                if (mDestroyed) {
                    bitmapToRelease = mBackBitmap;
                    mBackBitmap = null;
                } else if (mNextFrameToDecode >= 0 && mState == STATE_DECODING) {
                    schedule = true;
                    mNextSwap = invalidateTimeMs + mLastSwap;
                    mState = STATE_WAITING_TO_SWAP;
                }
            }
            if (schedule) {
                scheduleSelf(FrameSequenceDrawable.this, mNextSwap);
            }
            if (bitmapToRelease != null) {
                // destroy the bitmap here, since there's no safe way to get back to
                // drawable thread - drawable is likely detached, so schedule is noop.
                mBitmapProvider.releaseBitmap(bitmapToRelease);
            }
        }
    };

    private Runnable startRunnable = new Runnable() {
        @Override
        public void run() {
            if (mOnAnimationListener != null) {
                mOnAnimationListener.onStart(FrameSequenceDrawable.this);
            }
        }
    };

    private Runnable mCallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (mOnAnimationListener != null) {
                mOnAnimationListener.onFinished(FrameSequenceDrawable.this);
                currentLoopCount = 0;
            }
        }
    };

    private static Bitmap acquireAndValidateBitmap(BitmapProvider bitmapProvider,
                                                   int minWidth, int minHeight) {
        Bitmap bitmap = bitmapProvider.acquireBitmap(minWidth, minHeight);

        if (bitmap.getWidth() < minWidth
                || bitmap.getHeight() < minHeight
                || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            throw new IllegalArgumentException("Invalid bitmap provided");
        }

        return bitmap;
    }

    public FrameSequenceDrawable(FrameSequence frameSequence) {
        this(frameSequence, sAllocatingBitmapProvider);
    }

    public FrameSequenceDrawable(FrameSequence frameSequence, BitmapProvider bitmapProvider) {
        if (frameSequence == null || bitmapProvider == null) throw new IllegalArgumentException();

        mFrameSequence = frameSequence;
        mFrameSequenceState = frameSequence.createState();
        final int width = frameSequence.getWidth();
        final int height = frameSequence.getHeight();

        mBitmapProvider = bitmapProvider;
        mFrontBitmap = acquireAndValidateBitmap(bitmapProvider, width, height);
        mBackBitmap = acquireAndValidateBitmap(bitmapProvider, width, height);
        mSrcRect = new Rect(0, 0, width, height);
        mPaint = new Paint();
        mPaint.setFilterBitmap(true);

        mLastSwap = 0;

        mNextFrameToDecode = -1;
        mFrameSequenceState.getFrame(0, mFrontBitmap, -1);
        initializeDecodingThread();
    }

    private void checkDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("Cannot perform operation on recycled drawable");
        }
    }

    public boolean isDestroyed() {
        synchronized (mLock) {
            return mDestroyed;
        }
    }

    /**
     * Marks the drawable as permanently recycled (and thus unusable), and releases any owned
     * Bitmaps drawable to its BitmapProvider, if attached.
     * <p>
     * If no BitmapProvider is attached to the drawable, recycle() is called on the Bitmaps.
     */
    public void destroy() {
        if (mBitmapProvider == null) {
            throw new IllegalStateException("BitmapProvider must be non-null");
        }

        Bitmap bitmapToReleaseA;
        Bitmap bitmapToReleaseB = null;
        synchronized (mLock) {
            checkDestroyedLocked();

            bitmapToReleaseA = mFrontBitmap;
            mFrontBitmap = null;

            if (mState != STATE_DECODING) {
                bitmapToReleaseB = mBackBitmap;
                mBackBitmap = null;
            }

            mDestroyed = true;
        }

        // For simplicity and safety, we don't destroy the state object here
        mFrameSequenceState.destroy();
        mBitmapProvider.releaseBitmap(bitmapToReleaseA);
        if (bitmapToReleaseB != null) {
            mBitmapProvider.releaseBitmap(bitmapToReleaseB);
        }

        if (sDecodingThread != null) {
            sDecodingThread.quit();
            sDecodingThread = null;
        }
        if (sDecodingThreadHandler != null) {
            sDecodingThreadHandler.removeCallbacks(mDecodeRunnable);
            mDecodeRunnable = null;
        }

        unscheduleSelf(this);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mFrameSequenceState.destroy();
        } finally {
            super.finalize();
        }
    }

    private int currentLoopCount = 0;

    @Override
    public void draw(Canvas canvas) {
        synchronized (mLock) {
            checkDestroyedLocked();
            if (mState == STATE_WAITING_TO_SWAP) {
                // may have failed to schedule mark ready runnable,
                // so go ahead and swap if swapping is due
                if (mNextSwap - SystemClock.uptimeMillis() <= 0) {
                    mState = STATE_READY_TO_SWAP;
                }
            }

            if (isRunning() && mState == STATE_READY_TO_SWAP) {
                // Because draw has occurred, the view system is guaranteed to no longer hold a
                // reference to the old mFrontBitmap, so we now use it to produce the next frame
                Bitmap tmp = mBackBitmap;
                mBackBitmap = mFrontBitmap;
                mFrontBitmap = tmp;

                mLastSwap = SystemClock.uptimeMillis();

                int defaultLoopCount = mFrameSequence.getDefaultLoopCount();

                boolean continueLooping = true;
                if (mNextFrameToDecode >= mFrameSequence.getFrameCount() - 1) {

                    if (mLoopBehavior != LOOP_INF) {
                        if (currentLoopCount >= defaultLoopCount || mLoopBehavior == LOOP_ONCE) {
                            continueLooping = false;
                        }
                    }
                }

                if (continueLooping) {
                    scheduleDecodeLocked();
                } else {
                    scheduleSelf(mCallbackRunnable, 0);
                }
            }
        }

        canvas.drawBitmap(mFrontBitmap, mSrcRect, getBounds(), mPaint);
    }

    private void scheduleDecodeLocked() {
        mState = STATE_SCHEDULED;
        mNextFrameToDecode += 2;

        int defaultLoopCount = mFrameSequence.getDefaultLoopCount();

        if (mNextFrameToDecode > mFrameSequence.getFrameCount() - 1) {
            if (mLoopBehavior != LOOP_INF) {
                if (currentLoopCount >= defaultLoopCount || mLoopBehavior == LOOP_ONCE) {
                    mNextFrameToDecode = mFrameSequence.getFrameCount() - 1;
                } else {
                    mNextFrameToDecode = 0;
                    currentLoopCount++;
                }
            } else {
                mNextFrameToDecode = 0;
                currentLoopCount++;
            }
        }
        sDecodingThreadHandler.post(mDecodeRunnable);
    }

    @Override
    public void run() {
        if (mDestroyed) {
            return;
        }
        // set ready to swap as necessary
        boolean invalidate = false;
        synchronized (mLock) {
            if (mNextFrameToDecode >= 0 && mState == STATE_WAITING_TO_SWAP) {
                mState = STATE_READY_TO_SWAP;
                invalidate = true;
            }
        }
        if (invalidate) {
            invalidateSelf();
        }
    }

    @Override
    public void start() {
        if (!isRunning()) {
            synchronized (mLock) {
                checkDestroyedLocked();
                if (mState == STATE_SCHEDULED) return; // already scheduled
                scheduleSelf(startRunnable, 0);
                scheduleDecodeLocked();
            }
        }
    }

    @Override
    public void stop() {
        if (isRunning()) {
            unscheduleSelf(this);
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (mLock) {
            return mNextFrameToDecode > -1 && !mDestroyed;
        }
    }

    @Override
    public void unscheduleSelf(Runnable what) {
        synchronized (mLock) {
            mNextFrameToDecode = -1;
            mState = 0;
        }
        super.unscheduleSelf(what);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);

        if (!visible) {
            stop();
        } else if (restart || changed) {
            stop();
            start();
        }

        return changed;
    }

    // drawing properties

    @Override
    public void setFilterBitmap(boolean filter) {
        mPaint.setFilterBitmap(filter);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getIntrinsicWidth() {
        return mFrameSequence.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mFrameSequence.getHeight();
    }

    @Override
    public int getOpacity() {
        return mFrameSequence.isOpaque() ? PixelFormat.OPAQUE : PixelFormat.TRANSPARENT;
    }
}
