package com.android.launcher3.icons;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;

import com.android.launcher3.R;

import static android.graphics.Paint.DITHER_FLAG;
import static android.graphics.Paint.FILTER_BITMAP_FLAG;
import static com.android.launcher3.icons.ShadowGenerator.BLUR_FACTOR;

/**
 * This class will be moved to androidx library. There shouldn't be any dependency outside
 * this package.
 */
public class BaseIconFactory {

    private static final int DEFAULT_WRAPPER_BACKGROUND = Color.WHITE;
    public static final boolean ATLEAST_OREO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

    private final Rect mOldBounds = new Rect();
    private final Context mContext;
    private final Canvas mCanvas;
    private final PackageManager mPm;
    private final ColorExtractor mColorExtractor;
    private boolean mDisableColorExtractor;

    private int mFillResIconDpi;
    private int mIconBitmapSize;

    private IconNormalizer mNormalizer;
    private ShadowGenerator mShadowGenerator;

    private Drawable mWrapperIcon;
    private int mWrapperBackgroundColor;

    protected BaseIconFactory(Context context, int fillResIconDpi, int iconBitmapSize) {
        mContext = context.getApplicationContext();

        mFillResIconDpi = fillResIconDpi;
        mIconBitmapSize = iconBitmapSize;

        mPm = mContext.getPackageManager();
        mColorExtractor = new ColorExtractor();

        mCanvas = new Canvas();
        mCanvas.setDrawFilter(new PaintFlagsDrawFilter(DITHER_FLAG, FILTER_BITMAP_FLAG));
    }

    protected void clear() {
        mWrapperBackgroundColor = DEFAULT_WRAPPER_BACKGROUND;
        mDisableColorExtractor = false;
    }

    public ShadowGenerator getShadowGenerator() {
        if (mShadowGenerator == null) {
            mShadowGenerator = new ShadowGenerator(mIconBitmapSize);
        }
        return mShadowGenerator;
    }

    public IconNormalizer getNormalizer() {
        if (mNormalizer == null) {
            mNormalizer = new IconNormalizer(mContext, mIconBitmapSize);
        }
        return mNormalizer;
    }

    public BitmapInfo createIconBitmap(Intent.ShortcutIconResource iconRes) {
        try {
            Resources resources = mPm.getResourcesForApplication(iconRes.packageName);
            if (resources != null) {
                final int id = resources.getIdentifier(iconRes.resourceName, null, null);
                // do not stamp old legacy shortcuts as the app may have already forgotten about it
                return createBadgedIconBitmap(
                        resources.getDrawableForDensity(id, mFillResIconDpi),
                        Process.myUserHandle() /* only available on primary user */,
                        false /* do not apply legacy treatment */);
            }
        } catch (Exception e) {
            // Icon not found.
        }
        return null;
    }

    public BitmapInfo createIconBitmap(Bitmap icon) {
        if (mIconBitmapSize == icon.getWidth() && mIconBitmapSize == icon.getHeight()) {
            return BitmapInfo.fromBitmap(icon);
        }
        return BitmapInfo.fromBitmap(
                createIconBitmap(new BitmapDrawable(mContext.getResources(), icon), 1f));
    }

    public BitmapInfo createBadgedIconBitmap(Drawable icon, UserHandle user,
            boolean shrinkNonAdaptiveIcons) {
        return createBadgedIconBitmap(icon, user, shrinkNonAdaptiveIcons, false, null);
    }

    public BitmapInfo createBadgedIconBitmap(Drawable icon, UserHandle user,
            boolean shrinkNonAdaptiveIcons, boolean isInstantApp) {
        return createBadgedIconBitmap(icon, user, shrinkNonAdaptiveIcons, isInstantApp, null);
    }

    /**
     * Creates bitmap using the source drawable and various parameters.
     * The bitmap is visually normalized with other icons and has enough spacing to add shadow.
     *
     * @param icon                      source of the icon
     * @param user                      info can be used for a badge
     * @param shrinkNonAdaptiveIcons    {@code true} if non adaptive icons should be treated
     * @param isInstantApp              info can be used for a badge
     * @param scale                     returns the scale result from normalization
     * @return a bitmap suitable for disaplaying as an icon at various system UIs.
     */
    public BitmapInfo createBadgedIconBitmap(Drawable icon, UserHandle user,
            boolean shrinkNonAdaptiveIcons, boolean isInstantApp, float[] scale) {
        if (scale == null) {
            scale = new float[1];
        }
        icon = normalizeAndWrapToAdaptiveIcon(icon, shrinkNonAdaptiveIcons, null, scale);
        Bitmap bitmap = createIconBitmap(icon, scale[0]);
        if (ATLEAST_OREO && icon instanceof AdaptiveIconDrawable) {
            mCanvas.setBitmap(bitmap);
            getShadowGenerator().recreateIcon(Bitmap.createBitmap(bitmap), mCanvas);
            mCanvas.setBitmap(null);
        }

        final Bitmap result;
        if (user != null && !Process.myUserHandle().equals(user)) {
            BitmapDrawable drawable = new FixedSizeBitmapDrawable(bitmap);
            Drawable badged = mPm.getUserBadgedIcon(drawable, user);
            if (badged instanceof BitmapDrawable) {
                result = ((BitmapDrawable) badged).getBitmap();
            } else {
                result = createIconBitmap(badged, 1f);
            }
        } else if (isInstantApp) {
            badgeWithDrawable(bitmap, mContext.getDrawable(R.drawable.ic_instant_app_badge));
            result = bitmap;
        } else {
            result = bitmap;
        }
        return BitmapInfo.fromBitmap(result, mDisableColorExtractor ? null : mColorExtractor);
    }

    public Bitmap createScaledBitmapWithoutShadow(Drawable icon, boolean shrinkNonAdaptiveIcons) {
        RectF iconBounds = new RectF();
        float[] scale = new float[1];
        icon = normalizeAndWrapToAdaptiveIcon(icon, shrinkNonAdaptiveIcons, iconBounds, scale);
        return createIconBitmap(icon,
                Math.min(scale[0], ShadowGenerator.getScaleForBounds(iconBounds)));
    }

    /**
     * Sets the background color used for wrapped adaptive icon
     */
    public void setWrapperBackgroundColor(int color) {
        mWrapperBackgroundColor = (Color.alpha(color) < 255) ? DEFAULT_WRAPPER_BACKGROUND : color;
    }

    /**
     * Disables the dominant color extraction for all icons loaded.
     */
    public void disableColorExtraction() {
        mDisableColorExtractor = true;
    }

    private Drawable normalizeAndWrapToAdaptiveIcon(Drawable icon, boolean shrinkNonAdaptiveIcons,
            RectF outIconBounds, float[] outScale) {
        float scale = 1f;

        if (shrinkNonAdaptiveIcons) {
            boolean[] outShape = new boolean[1];
            if (mWrapperIcon == null) {
                mWrapperIcon = mContext.getDrawable(R.drawable.adaptive_icon_drawable_wrapper)
                        .mutate();
            }
            AdaptiveIconDrawable dr = (AdaptiveIconDrawable) mWrapperIcon;
            dr.setBounds(0, 0, 1, 1);
            scale = getNormalizer().getScale(icon, outIconBounds);
            if (ATLEAST_OREO && !(icon instanceof AdaptiveIconDrawable)) {
                FixedScaleDrawable fsd = ((FixedScaleDrawable) dr.getForeground());
                fsd.setDrawable(icon);
                fsd.setScale(scale);
                icon = dr;
                scale = getNormalizer().getScale(icon, outIconBounds);

                ((ColorDrawable) dr.getBackground()).setColor(mWrapperBackgroundColor);
            }
        } else {
            scale = getNormalizer().getScale(icon, outIconBounds);
        }

        outScale[0] = scale;
        return icon;
    }

    /**
     * Adds the {@param badge} on top of {@param target} using the badge dimensions.
     */
    public void badgeWithDrawable(Bitmap target, Drawable badge) {
        mCanvas.setBitmap(target);
        badgeWithDrawable(mCanvas, badge);
        mCanvas.setBitmap(null);
    }

    /**
     * Adds the {@param badge} on top of {@param target} using the badge dimensions.
     */
    public void badgeWithDrawable(Canvas target, Drawable badge) {
        int badgeSize = mContext.getResources().getDimensionPixelSize(R.dimen.profile_badge_size);
        badge.setBounds(mIconBitmapSize - badgeSize, mIconBitmapSize - badgeSize,
                mIconBitmapSize, mIconBitmapSize);
        badge.draw(target);
    }

    /**
     * @param scale the scale to apply before drawing {@param icon} on the canvas
     */
    private Bitmap createIconBitmap(Drawable icon, float scale) {
        Bitmap bitmap = Bitmap.createBitmap(mIconBitmapSize, mIconBitmapSize,
                Bitmap.Config.ARGB_8888);
        mCanvas.setBitmap(bitmap);
        mOldBounds.set(icon.getBounds());

        if (ATLEAST_OREO && icon instanceof AdaptiveIconDrawable) {
            int offset = Math.max((int) Math.ceil(BLUR_FACTOR * mIconBitmapSize),
                    Math.round(mIconBitmapSize * (1 - scale) / 2 ));
            icon.setBounds(offset, offset, mIconBitmapSize - offset, mIconBitmapSize - offset);
            icon.draw(mCanvas);
        } else {
            if (icon instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
                Bitmap b = bitmapDrawable.getBitmap();
                if (bitmap != null && b.getDensity() == Bitmap.DENSITY_NONE) {
                    bitmapDrawable.setTargetDensity(mContext.getResources().getDisplayMetrics());
                }
            }
            int width = mIconBitmapSize;
            int height = mIconBitmapSize;

            int intrinsicWidth = icon.getIntrinsicWidth();
            int intrinsicHeight = icon.getIntrinsicHeight();
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                // Scale the icon proportionally to the icon dimensions
                final float ratio = (float) intrinsicWidth / intrinsicHeight;
                if (intrinsicWidth > intrinsicHeight) {
                    height = (int) (width / ratio);
                } else if (intrinsicHeight > intrinsicWidth) {
                    width = (int) (height * ratio);
                }
            }
            final int left = (mIconBitmapSize - width) / 2;
            final int top = (mIconBitmapSize - height) / 2;
            icon.setBounds(left, top, left + width, top + height);
            mCanvas.save();
            mCanvas.scale(scale, scale, mIconBitmapSize / 2, mIconBitmapSize / 2);
            icon.draw(mCanvas);
            mCanvas.restore();

        }
        icon.setBounds(mOldBounds);
        mCanvas.setBitmap(null);
        return bitmap;
    }

    /**
     * An extension of {@link BitmapDrawable} which returns the bitmap pixel size as intrinsic size.
     * This allows the badging to be done based on the action bitmap size rather than
     * the scaled bitmap size.
     */
    private static class FixedSizeBitmapDrawable extends BitmapDrawable {

        public FixedSizeBitmapDrawable(Bitmap bitmap) {
            super(null, bitmap);
        }

        @Override
        public int getIntrinsicHeight() {
            return getBitmap().getWidth();
        }

        @Override
        public int getIntrinsicWidth() {
            return getBitmap().getWidth();
        }
    }
}