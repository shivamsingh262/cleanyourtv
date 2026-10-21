package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screensaver: the owner's photos as three prints on the home screen's time-of-day sky, each the whole photo on a white card
 * with its month and year written underneath. Every 15 seconds the three slide off to the left one after another
 * and three new ones are dealt in from the right, each at a slight tilt. The thin clock stays bottom left. Photos
 * come from files/photos (tools/photos.sh puts them there); with none it shows the clock scene (ClockDream)
 * instead. No network: nothing leaves the TV.
 */
public class PhotoDream extends DreamService {

    static final String DIR = "photos";
    private static final int PRINTS = 3;
    private static final long SHOW_MS = 15_000L;
    private static final long OUT_MS = 700L, OUT_STAGGER_MS = 120L;
    private static final long IN_MS = 800L, IN_STAGGER_MS = 180L;
    /** Photo size on its card, in dp: a landscape photo's width, a portrait one's height. */
    private static final int LANDSCAPE_W_DP = 300, PORTRAIT_H_DP = 320;
    /**
     * Where each print's centre aims (dp, on the 960 x 540 dp screen) and its tilt. The first print sits above
     * the clock: its height there is capped and its y worked out from the clock (place()), so it never covers it.
     */
    private static final int[][] REST = {{190, 0, -5}, {500, 270, 3}, {790, 255, -2}};
    /** The white card around the photo: 10 dp on three sides, a 38 dp strip for the date below. */
    private static final int CARD_SIDES_DP = 20, CARD_TOPBOTTOM_DP = 48;
    /** Space kept free at the screen's edges and around the clock. */
    private static final int EDGE_DP = 16, CLOCK_GAP_DP = 12;
    /** Random play around REST, so no two sets lie the same way. */
    private static final int JITTER_DP = 14, JITTER_DEG = 2;
    /** The most any print tilts: the steepest REST tilt plus JITTER_DEG. */
    private static final int MAX_TILT_DEG = 7;
    /** How far the clock may wander, so it does not burn in. */
    private static final int NUDGE_DP = 20;
    /**
     * The date the owner's file names carry (tools/photos.sh adds it): photo-0007-20231202.jpg, or from an older
     * upload only the month, photo-0007-202312.jpg. The print shows the month and year.
     */
    private static final Pattern NAME_DATE = Pattern.compile("-(\\d{4})(\\d{2})(\\d{2})?\\.[A-Za-z]+$");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService loader;
    private ClockScene clockScene;
    private View clock;
    private SkyView sky;
    private final View[] cards = new View[PRINTS];
    private final ImageView[] photoViews = new ImageView[PRINTS];
    private final TextView[] dateViews = new TextView[PRINTS];
    /** Loader thread only. */
    private final List<File> queue = new ArrayList<>();
    private float density;
    private int screenW;
    private boolean dealt;
    /** Bumped on stop, so a decode that finishes afterwards is dropped instead of shown. */
    private int generation;

    /** A photo ready for a card. */
    private static final class Print {
        Bitmap bitmap;
        String date;
        /** The photo's size on the card, px. */
        int w, h;
    }

    private final Runnable next = this::loadNext;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        if (photos().isEmpty()) {
            setContentView(R.layout.dream_clock);
            clockScene = new ClockScene(getWindow().getDecorView());
            return;
        }
        setContentView(R.layout.dream_photos);
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        sky = findViewById(R.id.photoSky);
        sky.setIntensity(Config.load(this).skyIntensity);   // Home's sky, a theme's sky_intensity included
        clock = findViewById(R.id.photoClock);
        ViewGroup stage = findViewById(R.id.prints);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < PRINTS; i++) {
            cards[i] = inflater.inflate(R.layout.dream_print, stage, false);
            photoViews[i] = cards[i].findViewById(R.id.printPhoto);
            dateViews[i] = cards[i].findViewById(R.id.printDate);
            cards[i].setTranslationX(screenW * 2f);   // off screen until the first deal
            stage.addView(cards[i]);
        }
        cards[1].bringToFront();   // the middle print lies on top of its neighbours
        loader = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (clockScene != null) {
            clockScene.start();
            return;
        }
        handler.post(next);
    }

    @Override
    public void onDreamingStopped() {
        stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stop();
        if (loader != null) loader.shutdownNow();
        for (ImageView v : photoViews) if (v != null) release(v);
        super.onDetachedFromWindow();
    }

    private void stop() {
        if (clockScene != null) clockScene.stop();
        generation++;
        handler.removeCallbacksAndMessages(null);
    }

    /** The photo files, rescanned each round so photos pushed while the TV is on show up. */
    private List<File> photos() {
        List<File> out = new ArrayList<>();
        File dir = new File(getExternalFilesDir(null), DIR);
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && ThemeFiles.collectionImage(f.getName())) out.add(f);
        }
        return out;
    }

    private void loadNext() {
        int gen = generation;
        // The first print lies above the clock: its tilted outline may be no taller than the room there.
        int aboveClock = clockBox().top - Math.round((EDGE_DP + CLOCK_GAP_DP) * density);
        loader.execute(() -> {
            Print[] batch = new Print[PRINTS];
            for (int i = 0; i < PRINTS; i++) {
                try {
                    batch[i] = nextPrint(i == 0 ? aboveClock : Integer.MAX_VALUE);
                } catch (OutOfMemoryError e) {
                    batch[i] = null;
                }
            }
            handler.post(() -> {
                if (gen != generation) {
                    for (Print p : batch) if (p != null && p.bitmap != null) p.bitmap.recycle();
                    return;
                }
                sweep(batch);
                handler.postDelayed(next, SHOW_MS);
            });
        });
    }

    /**
     * Loader thread only (it owns the queue): the next photo, decoded at its size on the card, shrunk if need be
     * so the card, tilted as far as it may be, is no more than maxSpan px tall.
     */
    private Print nextPrint(int maxSpan) {
        for (int tries = 0; tries < 4; tries++) {
            if (queue.isEmpty()) {
                queue.addAll(photos());
                Collections.shuffle(queue, random);
            }
            if (queue.isEmpty()) return null;
            File file = queue.remove(0);
            int[] size = size(file);
            if (size == null) continue;
            int[] box = box(size, maxSpan);
            Bitmap bitmap = decode(file, box[0], box[1]);
            if (bitmap == null) continue;
            Print p = new Print();
            p.bitmap = bitmap;
            p.date = dateOf(file);
            p.w = box[0];
            p.h = box[1];
            return p;
        }
        return null;
    }

    /**
     * The photo's size on its card in px: its own shape, so it is never cropped, and small enough that the card
     * tilted by the most any slot tilts stands no taller than maxSpan.
     */
    private int[] box(int[] size, int maxSpan) {
        float w, h;
        if (size[1] > size[0]) {
            h = PORTRAIT_H_DP * density;
            w = h * size[0] / size[1];
        } else {
            w = LANDSCAPE_W_DP * density;
            h = w * size[1] / size[0];
        }
        // A card of cw x ch tilted by a stands ch*cos(a) + cw*sin(a) tall; solve for the scale that meets maxSpan.
        double rad = Math.toRadians(MAX_TILT_DEG);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float sides = CARD_SIDES_DP * density, strips = CARD_TOPBOTTOM_DP * density;
        float span = (h + strips) * cos + (w + sides) * sin;
        if (span > maxSpan) {
            float scale = (maxSpan - strips * cos - sides * sin) / (h * cos + w * sin);
            w *= scale;
            h *= scale;
        }
        return new int[]{Math.round(w), Math.round(h)};
    }

    /**
     * The space the clock may take, px: where it is laid out, widened by how far nudgeClock() can move it.
     * Before the first layout, where its margins and size put it.
     */
    private Rect clockBox() {
        int nudge = Math.round(NUDGE_DP * density);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (clock.getHeight() == 0) {
            return new Rect(0, screenH - Math.round(200 * density), Math.round(360 * density), screenH);
        }
        return new Rect(clock.getLeft() - nudge, clock.getTop() - nudge, clock.getRight() + nudge, clock.getBottom());
    }

    /**
     * Places a card of cardW x cardH px tilted by tilt degrees near slot's resting spot: whole on the screen, and,
     * as it lies tilted, clear of the clock. A card that would reach the clock goes above it, or else to its right.
     */
    private void place(View card, int slot, int cardW, int cardH, float tilt) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float edge = EDGE_DP * density, gap = CLOCK_GAP_DP * density;
        Rect clockBox = clockBox();
        // Half the width and height of the tilted card's outline.
        double rad = Math.toRadians(Math.abs(tilt));
        float hw = (float) (cardW * Math.cos(rad) + cardH * Math.sin(rad)) / 2f;
        float hh = (float) (cardW * Math.sin(rad) + cardH * Math.cos(rad)) / 2f;
        float jx = (random.nextFloat() * 2 - 1) * JITTER_DP * density;
        float jy = (random.nextFloat() * 2 - 1) * JITTER_DP * density;
        float cx = REST[slot][0] * density + jx;
        float cy = slot == 0 ? (edge + clockBox.top - gap) / 2f + jy : REST[slot][1] * density + jy;
        cx = Math.max(edge + hw, Math.min(screenW - edge - hw, cx));
        cy = Math.max(edge + hh, Math.min(screenH - edge - hh, cy));
        if (cx - hw < clockBox.right + gap && cy + hh > clockBox.top - gap) {
            float above = clockBox.top - gap - hh;
            if (above - hh >= edge) cy = above;
            else cx = clockBox.right + gap + hw;
        }
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) card.getLayoutParams();
        cp.leftMargin = Math.round(cx - cardW / 2f);
        cp.topMargin = Math.round(cy - cardH / 2f);
        card.setLayoutParams(cp);
    }

    /** Slides the prints on screen off to the left, one after another, then deals the new ones in from the right. */
    private void sweep(Print[] batch) {
        long dealAt = 0;
        if (dealt) {
            for (int i = 0; i < PRINTS; i++) {
                View card = cards[i];
                card.animate().translationX(-(screenW + card.getWidth() + 200f))
                        .rotation(card.getRotation() - 12f)
                        .setStartDelay(i * OUT_STAGGER_MS).setDuration(OUT_MS)
                        .setInterpolator(new AccelerateInterpolator(1.6f)).start();
            }
            dealAt = OUT_MS + (PRINTS - 1) * OUT_STAGGER_MS + 60;
        }
        dealt = true;
        handler.postDelayed(() -> deal(batch), dealAt);
        nudgeClock();
        sky.update();   // a new colour at most once a minute; the sweep comes round every 15 seconds
    }

    private void deal(Print[] batch) {
        for (int i = 0; i < PRINTS; i++) {
            Print p = batch[i];
            View card = cards[i];
            ImageView photo = photoViews[i];
            release(photo);
            if (p == null) {
                card.setVisibility(View.INVISIBLE);
                continue;
            }
            card.setVisibility(View.VISIBLE);
            photo.setImageBitmap(p.bitmap);
            ViewGroup.LayoutParams lp = photo.getLayoutParams();
            lp.width = p.w;
            lp.height = p.h;
            photo.setLayoutParams(lp);
            dateViews[i].setText(p.date);
            float tilt = REST[i][2] + (random.nextFloat() * 2 - 1) * JITTER_DEG;
            place(card, i, p.w + Math.round(CARD_SIDES_DP * density), p.h + Math.round(CARD_TOPBOTTOM_DP * density),
                    tilt);
            card.animate().cancel();
            card.setTranslationX(screenW + 200f);
            card.setRotation(tilt + 12f);
            card.animate().translationX(0f).rotation(tilt)
                    .setStartDelay(i * IN_STAGGER_MS).setDuration(IN_MS)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        clock.bringToFront();
    }

    private void nudgeClock() {
        float d = density * NUDGE_DP;
        clock.animate().translationX((random.nextFloat() * 2 - 1) * d)
                .translationY(random.nextFloat() * -d).setStartDelay(0).setDuration(1500).start();
    }

    /** Drops the view's photo and frees its pixels now rather than at the next GC. */
    private static void release(ImageView view) {
        Drawable d = view.getDrawable();
        view.setImageDrawable(null);
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    /** "Dec 2023": from the file name tools/photos.sh gave it, else from when the photo was taken; else "". */
    static String dateOf(File file) {
        int year = -1, month = -1;
        Matcher m = NAME_DATE.matcher(file.getName());
        if (m.find()) {
            year = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(2));
        } else {
            try {
                String taken = new ExifInterface(file.getPath()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                if (taken != null && taken.length() >= 7) {   // "2023:12:02 09:22:30"
                    year = Integer.parseInt(taken.substring(0, 4));
                    month = Integer.parseInt(taken.substring(5, 7));
                }
            } catch (Exception ignored) {
                // No date: the strip under the photo stays blank.
            }
        }
        if (year < 1900 || month < 1 || month > 12) return "";
        return new DateFormatSymbols().getShortMonths()[month - 1] + " " + year;
    }

    /** The EXIF rotation that turns the photo upright: 0, 90, 180 or 270. */
    private static int rotation(File file) {
        try {
            int o = new ExifInterface(file.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
            // PNG and WebP carry no EXIF here; show them as they are.
        }
        return 0;
    }

    /** The photo's {width, height} once upright, read from its header only; null if it is not an image. */
    static int[] uprightSize(File file) {
        return size(file);
    }

    private static int[] size(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        return rotation(file) % 180 == 0 ? new int[]{bounds.outWidth, bounds.outHeight}
                : new int[]{bounds.outHeight, bounds.outWidth};
    }

    /** Decodes at the smallest power-of-two size that still covers w x h, upright per EXIF. */
    static Bitmap decode(File file, int w, int h) {
        try {
            int rotation = rotation(file);
            int[] size = size(file);
            if (size == null) return null;
            int sample = 1;
            while (size[0] / (sample * 2) >= w && size[1] / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory of ARGB_8888; photos have no alpha
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bitmap == null || rotation == 0) return bitmap;
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }
}
