package com.winlator.cmod.tour;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.winlator.cmod.R;

import java.util.List;
import java.util.function.Supplier;

/**
 * A step-by-step walkthrough laid over the whole activity: everything is dimmed except a
 * spotlight on the button the current step is about, with a bubble explaining it underneath.
 *
 * Tapping inside the spotlight presses the real button and moves on, so the user learns the
 * app by using it; Next moves on without pressing anything. Everything outside the spotlight
 * and the bubble is blocked while the tour is up.
 */
public class GuidedTour extends FrameLayout {
    public static class Step {
        final int textResId;
        /** Finds the view to spotlight. Asked repeatedly until it returns one that is laid out. */
        final Supplier<View> target;
        /** Run once on entering the step, before the target is looked for. May be null. */
        final Runnable prepare;
        /** Whether a tap in the spotlight presses what is under it, or only moves on. */
        boolean press = true;
        /** A second view the spotlight is widened to take in, for a step about two neighbours. May be null. */
        Supplier<View> alsoTarget;

        public Step(int textResId, Supplier<View> target, Runnable prepare) {
            this.textResId = textResId;
            this.target = target;
            this.prepare = prepare;
        }

        /** For a step that only points something out: pressing it would open something mid-tour. */
        public Step noPress() {
            press = false;
            return this;
        }

        public Step alsoSpotlight(Supplier<View> view) {
            alsoTarget = view;
            return this;
        }
    }

    /** Screens switched by a step's prepare come up asynchronously, so its target is polled for. */
    private static final long FIND_INTERVAL_MS = 100;
    private static final long FIND_TIMEOUT_MS = 3000;
    /**
     * How long the bubble waits for its target before showing on its own. The target may never
     * come, such as a row of an empty list, and a hidden bubble leaves no Next or Skip to press.
     */
    private static final long SHOW_WITHOUT_TARGET_MS = 400;

    private final List<Step> steps;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF hole = new RectF();
    private final float holePadding;
    private final float holeRadius;
    private final int margin;

    private final View bubble;
    private final TextView tvCount;
    private final TextView tvText;
    private final TextView btBack;
    private final TextView btNext;

    private int index = -1;
    private View target;
    private long findStart;
    private long findUntil;
    private boolean downInHole;

    private final Runnable findTarget = new Runnable() {
        @Override
        public void run() {
            View view = steps.get(index).target.get();
            if (view != null && view.isShown() && view.getWidth() > 0) {
                target = view;
                // Settings further down a page are scrolled to before being pointed at.
                view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
                post(GuidedTour.this::layoutAroundTarget);
            } else if (System.currentTimeMillis() < findUntil) {
                // Still looking, but the text is shown meanwhile; it moves onto the target if
                // that turns up.
                if (bubble.getVisibility() != VISIBLE
                        && System.currentTimeMillis() - findStart >= SHOW_WITHOUT_TARGET_MS) {
                    layoutAroundTarget();
                }
                handler.postDelayed(this, FIND_INTERVAL_MS);
            } else {
                // Not on screen after all; the bubble still explains, just without a spotlight.
                layoutAroundTarget();
            }
        }
    };

    private final ViewTreeObserver.OnGlobalLayoutListener onLayout = () -> {
        if (target != null) layoutAroundTarget();
    };

    private final ViewTreeObserver.OnScrollChangedListener onScroll = () -> {
        if (target != null) layoutAroundTarget();
    };

    public static GuidedTour start(Activity activity, List<Step> steps) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        GuidedTour tour = new GuidedTour(activity, steps);
        root.addView(tour, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        tour.requestFocus();
        tour.go(0);
        return tour;
    }

    private GuidedTour(Context context, List<Step> steps) {
        super(context);
        this.steps = steps;
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);

        float density = getResources().getDisplayMetrics().density;
        holePadding = 6 * density;
        holeRadius = 10 * density;
        margin = (int)(16 * density);

        dimPaint.setColor(Color.argb(180, 0, 0, 0));
        path.setFillType(Path.FillType.EVEN_ODD);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3 * density);
        ringPaint.setColor(ContextCompat.getColor(context, R.color.colorAccent));

        bubble = LayoutInflater.from(context).inflate(R.layout.guided_tour_bubble, this, false);
        addView(bubble);
        tvCount = bubble.findViewById(R.id.TVTourCount);
        tvText = bubble.findViewById(R.id.TVTourText);
        btBack = bubble.findViewById(R.id.BTTourBack);
        btNext = bubble.findViewById(R.id.BTTourNext);
        bubble.findViewById(R.id.BTTourSkip).setOnClickListener(v -> finish());
        btBack.setOnClickListener(v -> go(index - 1));
        btNext.setOnClickListener(v -> go(index + 1));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(onLayout);
        getViewTreeObserver().addOnScrollChangedListener(onScroll);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(onLayout);
        getViewTreeObserver().removeOnScrollChangedListener(onScroll);
        handler.removeCallbacks(findTarget);
        super.onDetachedFromWindow();
    }

    private void go(int newIndex) {
        if (newIndex < 0) return;
        if (newIndex >= steps.size()) {
            finish();
            return;
        }

        handler.removeCallbacks(findTarget);
        index = newIndex;
        target = null;
        hole.setEmpty();
        bubble.setVisibility(INVISIBLE);
        invalidate();

        Step step = steps.get(index);
        tvCount.setText(getContext().getString(R.string.tour_step_count, index + 1, steps.size()));
        tvText.setText(step.textResId);
        btBack.setVisibility(index > 0 ? VISIBLE : GONE);
        btNext.setText(index == steps.size() - 1 ? R.string.tour_done : R.string.tour_next);

        if (step.prepare != null) step.prepare.run();
        findStart = System.currentTimeMillis();
        findUntil = findStart + FIND_TIMEOUT_MS;
        handler.post(findTarget);
    }

    public void finish() {
        ViewGroup parent = (ViewGroup)getParent();
        if (parent != null) parent.removeView(this);
    }

    /** Puts the spotlight on the target and the bubble below it, or above when there is no room. */
    private void layoutAroundTarget() {
        if (getWidth() == 0) {
            post(this::layoutAroundTarget);
            return;
        }

        if (target != null && target.isShown()) {
            int[] own = new int[2];
            int[] other = new int[2];
            getLocationInWindow(own);
            target.getLocationInWindow(other);
            float left = other[0] - own[0];
            float top = other[1] - own[1];
            hole.set(left - holePadding, top - holePadding,
                    left + target.getWidth() + holePadding, top + target.getHeight() + holePadding);

            Step step = steps.get(index);
            View also = step.alsoTarget != null ? step.alsoTarget.get() : null;
            if (also != null && also.isShown()) {
                also.getLocationInWindow(other);
                left = other[0] - own[0];
                top = other[1] - own[1];
                hole.union(left - holePadding, top - holePadding,
                        left + also.getWidth() + holePadding, top + also.getHeight() + holePadding);
            }
        } else {
            hole.setEmpty();
        }

        int width = Math.min(getWidth() - 2 * margin, (int)(360 * getResources().getDisplayMetrics().density));
        bubble.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
        int height = bubble.getMeasuredHeight();

        float x, y;
        if (hole.isEmpty()) {
            x = (getWidth() - width) / 2f;
            y = (getHeight() - height) / 2f;
        } else {
            x = hole.centerX() - width / 2f;
            y = hole.bottom + margin;
            if (y + height > getHeight() - margin) y = hole.top - margin - height;
        }
        x = Math.max(margin, Math.min(x, getWidth() - margin - width));
        y = Math.max(margin, Math.min(y, getHeight() - margin - height));

        LayoutParams params = (LayoutParams)bubble.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            bubble.setLayoutParams(params);
        }
        bubble.setTranslationX(x);
        bubble.setTranslationY(y);
        bubble.setVisibility(VISIBLE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        path.reset();
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        if (!hole.isEmpty()) path.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW);
        canvas.drawPath(path, dimPaint);
        if (!hole.isEmpty()) canvas.drawRoundRect(hole, holeRadius, holeRadius, ringPaint);
    }

    /** Reached only by touches the bubble did not take: they are all swallowed but the spotlight's. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean inHole = target != null && hole.contains(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> downInHole = inHole;
            case MotionEvent.ACTION_UP -> {
                if (downInHole && inHole) pressTarget(event.getX(), event.getY());
                downInHole = false;
            }
            case MotionEvent.ACTION_CANCEL -> downInHole = false;
        }
        return true;
    }

    /**
     * Presses the real button under the tap; a spotlight can cover several, such as the store
     * tiles. On the last step the tour goes first, so whatever it opens is not covered.
     */
    private void pressTarget(float x, float y) {
        if (!steps.get(index).press) {
            go(index + 1);
            return;
        }

        View view = clickableAt(target, x, y);
        if (view == null) view = target;
        if (index == steps.size() - 1) {
            finish();
            view.performClick();
        } else {
            view.performClick();
            go(index + 1);
        }
    }

    /** The topmost clickable view under a point in this overlay's coordinates, or null. */
    private View clickableAt(View view, float x, float y) {
        if (!view.isShown()) return null;
        int[] own = new int[2];
        int[] other = new int[2];
        getLocationInWindow(own);
        view.getLocationInWindow(other);
        float left = other[0] - own[0];
        float top = other[1] - own[1];
        if (x < left || y < top || x >= left + view.getWidth() || y >= top + view.getHeight()) return null;

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = clickableAt(group.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return view.isClickable() ? view : null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
