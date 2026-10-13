package com.roohu.phoneagent;

import android.Manifest;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;

@CapacitorPlugin(name = "Agent", permissions = {@Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "mic")})
public class AgentPlugin extends Plugin {
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer sr;

    private AgentAccessibilityService svc() { return AgentAccessibilityService.instance; }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject r = new JSObject();
        r.put("enabled", svc() != null);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void run(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        if (!s.runPlan(call.getString("plan", "[]"))) { call.reject("Agent is busy or the plan is bad"); return; }
        call.resolve();
    }

    @PluginMethod
    public void runAgent(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        if (!s.runAgent(call.getString("goal", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""))) {
            call.reject("Busy"); return;
        }
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        AgentAccessibilityService s = svc();
        JSObject r = new JSObject();
        r.put("status", s == null ? "{}" : s.getStatusJson());
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s != null) s.stopNow();
        call.resolve();
    }

    @PluginMethod
    public void botStart(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        s.startBot(call.getString("token", ""), call.getString("code", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""));
        call.resolve();
    }

    @PluginMethod
    public void botStop(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s != null) s.stopBot();
        call.resolve();
    }

    @PluginMethod
    public void speak(final PluginCall call) {
        final String t = call.getString("text", "");
        if (tts == null) {
            tts = new TextToSpeech(getContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "r");
            });
        } else if (ttsReady) {
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "r");
        }
        call.resolve();
    }

    @PluginMethod
    public void shutUp(PluginCall call) {
        if (tts != null) tts.stop();
        call.resolve();
    }

    @PluginMethod
    public void sound(PluginCall call) {
        String k = call.getString("k", "send");
        int[] f = k.equals("start") ? new int[]{523, 659, 784} : k.equals("done") ? new int[]{784, 1047} : k.equals("err") ? new int[]{330, 247} : new int[]{880};
        play(f);
        call.resolve();
    }

    private void play(final int[] f) {
        new Thread(() -> {
            try {
                int rate = 22050;
                int n = rate * 120 / 1000;
                short[] buf = new short[n * f.length];
                for (int k = 0; k < f.length; k++) {
                    for (int i = 0; i < n; i++) {
                        double env = Math.min(1.0, Math.min(i / 400.0, (n - i) / 600.0));
                        buf[k * n + i] = (short) (Math.sin(2 * Math.PI * f[k] * i / rate) * env * 7000);
                    }
                }
                AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, buf.length * 2, AudioTrack.MODE_STATIC);
                t.write(buf, 0, buf.length);
                t.play();
                Thread.sleep(120L * f.length + 250);
                t.release();
            } catch (Exception e) { }
        }).start();
    }

    @PluginMethod
    public void listen(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micDone");
        } else {
            startListen(call);
        }
    }

    @PermissionCallback
    private void micDone(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) startListen(call);
        else call.reject("Microphone permission was denied");
    }

    private void startListen(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
                call.reject("Speech recognition is not available on this phone");
                return;
            }
            if (tts != null) tts.stop();
            if (sr != null) sr.destroy();
            sr = SpeechRecognizer.createSpeechRecognizer(getContext());
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { }
                public void onPartialResults(Bundle b) { }
                public void onEvent(int t, Bundle b) { }
                public void onError(int e) { call.reject("I could not hear you (code " + e + ")"); }
                public void onResults(Bundle b) {
                    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    JSObject r = new JSObject();
                    r.put("text", l != null && !l.isEmpty() ? l.get(0) : "");
                    call.resolve(r);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            sr.startListening(i);
        });
    }
}
