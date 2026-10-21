package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
 * and three new ones are dealt in from the right, each at a slight tilt. A line along the bottom carries the
 * time, the date and Home's weather. Photos
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
    /** Each print's tilt, in degrees (the third value); deal() works out where each one lies. */
    private static final int[][] REST = {{0, 0, -5}, {0, 0, 3}, {0, 0, -2}};
    /** The white card around the photo: 10 dp on three sides, a 38 dp strip for the date below. */
    private static final int CARD_SIDES_DP = 20, CARD_TOPBOTTOM_DP = 48;
    /** Space kept free at the screen's edges and around the clock. */
    /** The gap at the screen's left and right edges and at its top; between two prints; above the clock line. */
    private static final int SIDE_DP = 48, TOP_DP = 20, COLUMN_GAP_DP = 24, STRIP_GAP_DP = 4;

    /** Random play around REST, so no two sets lie the same way. */
    private static final int JITTER_DP = 14, JITTER_DEG = 2;
    /** The most any print tilts: the steepest REST tilt plus JITTER_DEG. */
    private static final int MAX_TILT_DEG = 7;
    /** How far the clock may wander, so it does not burn in. */
    private static final int NUDGE_DP = 20;
    /** Up and down the clock line and the prints move much less than sideways: the room above the line is photos'. */
    private static final int NUDGE_UP_DP = 6, JITTER_Y_DP = 6;
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
            // Drawn into a layer with a clear margin, the tilted card is filtered as one image: smooth edges.
            cards[i].setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
        loader.execute(() -> {
            Print[] batch = new Print[PRINTS];
            for (int i = 0; i < PRINTS; i++) {
                try {
                    batch[i] = nextPrint(Integer.MAX_VALUE);
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

    /** The top of the clock line, px, less how far nudgeClock() can lift it. */
    private int stripTop() {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (clock.getHeight() == 0) return screenH - Math.round(120 * density);
        return clock.getTop() - Math.round(NUDGE_UP_DP * density);
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
        showInfo();
    }

    /**
     * Lays the set out in three equal columns above the clock line: each print is as large as its column allows,
     * tilted as far as it may be, and sits in the middle of it with a little play so no two sets lie the same way.
     */
    private void deal(Print[] batch) {
        float side = SIDE_DP * density, top = TOP_DP * density, jitter = JITTER_DP * density;
        float colGap = COLUMN_GAP_DP * density;
        int shadowRoom = getResources().getDimensionPixelSize(R.dimen.print_shadow_room);
        float areaTop = top, areaBottom = stripTop() - STRIP_GAP_DP * density;
        float colW = (screenW - 2 * side - (PRINTS - 1) * colGap) / PRINTS;
        // The box a tilted card must fit, leaving room for the play either way.
        float jitterY = JITTER_Y_DP * density;
        float boxW = colW - 2 * jitter, boxH = areaBottom - areaTop - 2 * jitterY;
        float sides = CARD_SIDES_DP * density, strips = CARD_TOPBOTTOM_DP * density;
        double rad = Math.toRadians(MAX_TILT_DEG);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        for (int i = 0; i < PRINTS; i++) {
            Print p = batch[i];
            View card = cards[i];
            ImageView photo = photoViews[i];
            release(photo);
            if (p == null) {
                card.setVisibility(View.INVISIBLE);
                continue;
            }
            // A photo h tall and a*h wide on its card, tilted, spans (a*h + sides)cos + (h + strips)sin across and
            // (a*h + sides)sin + (h + strips)cos down: the largest h that fits both ways.
            float a = (float) p.bitmap.getWidth() / p.bitmap.getHeight();
            float h = Math.min((boxW - sides * cos - strips * sin) / (a * cos + sin),
                    (boxH - sides * sin - strips * cos) / (a * sin + cos));
            p.h = Math.max(1, Math.round(h));
            p.w = Math.max(1, Math.round(h * a));
            int cw = p.w + Math.round(sides), ch = p.h + Math.round(strips);
            float cx = side + colW / 2f + i * (colW + colGap) + (random.nextFloat() * 2 - 1) * jitter;
            float cy = (areaTop + areaBottom) / 2f + (random.nextFloat() * 2 - 1) * jitterY;
            float tilt = REST[i][2] + (random.nextFloat() * 2 - 1) * JITTER_DEG;
            card.setVisibility(View.VISIBLE);
            photo.setImageBitmap(p.bitmap);
            ViewGroup.LayoutParams lp = photo.getLayoutParams();
            lp.width = p.w;
            lp.height = p.h;
            photo.setLayoutParams(lp);
            dateViews[i].setText(p.date);
            FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) card.getLayoutParams();
            cp.leftMargin = Math.round(cx - cw / 2f) - shadowRoom;
            cp.topMargin = Math.round(cy - ch / 2f) - shadowRoom;
            card.setLayoutParams(cp);
            card.animate().cancel();
            card.setTranslationX(screenW + 200f);
            card.setRotation(tilt + 12f);
            card.animate().translationX(0f).rotation(tilt)
                    .setStartDelay(i * IN_STAGGER_MS).setDuration(IN_MS)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        clock.bringToFront();
    }

    /** Home's weather and next sunrise or sunset at the end of the clock line, from the reading Home last kept. */
    private void showInfo() {
        TextView info = findViewById(R.id.photoInfo);
        if (info == null) return;
        CharSequence text = HomeInfo.lineFor(this, info);
        info.setText(text);
        info.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void nudgeClock() {
        float d = density * NUDGE_DP;
        clock.animate().translationX((random.nextFloat() * 2 - 1) * d)
                .translationY(random.nextFloat() * -NUDGE_UP_DP * density).setStartDelay(0).setDuration(1500).start();
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
