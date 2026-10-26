package de.greenhell.coach;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private LapTracker tracker = new LapTracker();
    private PreviewView preview;
    private ImageView cropPreview;
    private TextView status, summary, recognized;
    private TextToSpeech speech;
    private boolean speechReady, listening, destroyed;
    private volatile boolean busy;
    private volatile int x = 50, y = 50, width = 35, height = 15;
    private long lastFrame;
    private ProcessCameraProvider provider;
    private Button start;
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) openCamera();
            else status.setText("Kamerazugriff fehlt. Über „Kamera erlauben“ erneut versuchen oder in den Android-Einstellungen freigeben.");
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 12);
        // Keep controls clear of system bars when targeting Android 15.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(20 + insets.getSystemWindowInsetLeft(), 20 + insets.getSystemWindowInsetTop(),
                20 + insets.getSystemWindowInsetRight(), 12 + insets.getSystemWindowInsetBottom()); return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        label(root, "GREEN HELL COACH", 24);
        label(root, "Handy auf den Bildschirm richten. Wähle nur die eingefrorene LETZTE RUNDENZEIT, ohne Bestzeit oder laufenden Timer.", 15);
        preview = new PreviewView(this); preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 420));
        cropPreview = new ImageView(this); cropPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(cropPreview, new LinearLayout.LayoutParams(-1, 110));
        label(root, "Gelesener Ausschnitt (oben) – Position und Größe anpassen:", 14);
        slider(root, "Horizontal", x, 0); slider(root, "Vertikal", y, 1);
        slider(root, "Breite", width, 2); slider(root, "Höhe", height, 3);
        status = label(root, "Kamera wird gestartet …", 16);
        recognized = label(root, "Erkannte Zeit: –", 18);
        summary = label(root, tracker.summary(), 16);
        start = new Button(this); start.setText("Coaching starten"); root.addView(start);
        start.setOnClickListener(v -> {
            listening = !listening;
            start.setText(listening ? "Coaching pausieren" : "Coaching starten");
            if (!listening && speech != null) speech.stop();
            status.setText(listening ? "Aktiv: Warte auf eine 1,8 Sekunden stabile Rundenzeit." : "Coaching pausiert.");
        });
        Button reset = new Button(this); reset.setText("Neue Sitzung"); root.addView(reset);
        reset.setOnClickListener(v -> { tracker = new LapTracker(); summary.setText(tracker.summary()); if (speech != null) speech.stop(); });
        Button camera = new Button(this); camera.setText("Kamera erlauben / erneut starten"); root.addView(camera);
        camera.setOnClickListener(v -> requestCamera());
        speech = new TextToSpeech(this, result -> {
            if (destroyed) return;
            if (result == TextToSpeech.SUCCESS) {
                int language = speech.setLanguage(Locale.GERMANY);
                speechReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
            }
            if (!speechReady) status.setText("Deutsche Sprachausgabe fehlt. Bitte deutsche Sprachdaten in Android installieren. Textfeedback ist verfügbar.");
        });
        requestCamera();
    }
    private TextView label(LinearLayout root, String text, int size) {
        TextView label = new TextView(this); label.setText(text); label.setTextSize(size); root.addView(label); return label;
    }
    private void slider(LinearLayout root, String name, int value, int axis) {
        TextView title = label(root, name + ": " + value + "%", 13);
        SeekBar bar = new SeekBar(this); bar.setMax(100); bar.setProgress(value); root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                int v = axis > 1 ? Math.max(5, p) : p;
                if (axis == 0) x = v; else if (axis == 1) y = v; else if (axis == 2) width = v; else height = v;
                title.setText(name + ": " + v + "%");
                if (user) { listening = false; tracker = new LapTracker(); summary.setText(tracker.summary()); start.setText("Coaching starten"); if (speech != null) speech.stop(); }
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
    }
    private void requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        else permission.launch(Manifest.permission.CAMERA);
    }
    private void openCamera() {
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (destroyed) return;
            try {
                provider = future.get(); provider.unbindAll();
                Preview p = new Preview.Builder().build(); p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, this::analyze);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis);
                status.setText("Kamera bereit. Ausschnitt einstellen und Coaching starten.");
            } catch (Exception e) { status.setText("Kamera konnte nicht gestartet werden. Bitte erneut versuchen."); }
        }, ContextCompat.getMainExecutor(this));
    }
    private void analyze(ImageProxy frame) {
        long now = SystemClock.elapsedRealtime();
        if (busy || now - lastFrame < 350) { frame.close(); return; }
        busy = true; lastFrame = now;
        try {
            Bitmap raw = frame.toBitmap();
            Matrix matrix = new Matrix(); matrix.postRotate(frame.getImageInfo().getRotationDegrees());
            Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
            int w = Math.max(1, rotated.getWidth()*width/100), h = Math.max(1, rotated.getHeight()*height/100);
            int left = (rotated.getWidth()-w)*x/100, top = (rotated.getHeight()-h)*y/100;
            Bitmap crop = Bitmap.createBitmap(rotated, left, top, w, h);
            frame.close();
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener(result -> {
                    if (destroyed) return;
                    cropPreview.setImageBitmap(crop);
                    int value = LapTracker.parse(result.getText());
                    recognized.setText("Erkannte Zeit: " + LapTracker.format(value));
                    if (!listening) return;
                    String message = tracker.observe(result.getText(), SystemClock.elapsedRealtime());
                    if (message != null) {
                        status.setText(message); summary.setText(tracker.summary());
                        if (speechReady) speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "lap");
                    } else if (value < 0) status.setText("Keine eindeutige Zeit. Erwartet: 8:12.345 oder 8:12,345.");
                })
                .addOnFailureListener(e -> { if (!destroyed) status.setText("Texterkennung fehlgeschlagen. Beleuchtung und Ausschnitt prüfen."); })
                .addOnCompleteListener(task -> busy = false);
        } catch (Exception e) { frame.close(); busy = false; }
    }
    @Override protected void onStop() {
        super.onStop(); listening = false;
        if (start != null) start.setText("Coaching starten");
        if (speech != null) speech.stop();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (provider != null) provider.unbindAll();
        cameraExecutor.shutdown(); recognizer.close();
        if (speech != null) { speech.stop(); speech.shutdown(); }
        super.onDestroy();
    }
}
