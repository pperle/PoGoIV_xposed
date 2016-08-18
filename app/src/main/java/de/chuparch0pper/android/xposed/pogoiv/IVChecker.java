package de.chuparch0pper.android.xposed.pogoiv;

import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.NotificationCompat;
import android.widget.Toast;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import POGOProtos.Data.Capture.CaptureProbabilityOuterClass;
import POGOProtos.Data.PokemonDataOuterClass;
import POGOProtos.Networking.Envelopes.RequestEnvelopeOuterClass;
import POGOProtos.Networking.Envelopes.ResponseEnvelopeOuterClass;
import POGOProtos.Networking.Requests.RequestTypeOuterClass;
import POGOProtos.Networking.Responses.EncounterResponseOuterClass;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class IVChecker implements IXposedHookLoadPackage {
    private static final Map<Long, List<RequestTypeOuterClass.RequestType>> requestMap = new HashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.packageName.equals("com.nianticlabs.pokemongo"))
            return;

        final Class NiaNetClass = loadPackageParam.classLoader.loadClass("com.nianticlabs.nia.network.NiaNet");

        findAndHookMethod(NiaNetClass, "doSyncRequest", long.class, int.class, String.class, int.class, String.class, ByteBuffer.class, int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ByteBuffer requestBody = (ByteBuffer) param.args[5];
                int bodyOffset = (int) param.args[6];
                int bodySize = (int) param.args[7];

                if (requestBody == null || bodySize <= 0) {
                    XposedBridge.log("Error while getting data, requestBody =" + bodySize + " or bodySize = " + bodySize + " is not valid");
                    return;
                }

                ByteBuffer dRequestBody = requestBody.duplicate();
                byte[] bytes = new byte[bodySize];
                dRequestBody.get(bytes, bodyOffset, bodySize);

                RequestEnvelopeOuterClass.RequestEnvelope requestEnvelop = RequestEnvelopeOuterClass.RequestEnvelope.parseFrom(bytes);
                long requestId = requestEnvelop.getRequestId();

                List<RequestTypeOuterClass.RequestType> requestList = new ArrayList<RequestTypeOuterClass.RequestType>();
                for (int i = 0; i < requestEnvelop.getRequestsCount(); i++) {
                    // XposedBridge.log("Request " + requestEnvelop.getRequests(i).getRequestType().toString());
                    requestList.add(requestEnvelop.getRequests(i).getRequestType());
                }

                requestMap.put(requestId, requestList);
            }
        });

        findAndHookMethod(NiaNetClass, "readDataSteam", HttpURLConnection.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                int bodySize = (int) param.getResult();
                byte[] bytes = new byte[bodySize];

                Field bufferField = NiaNetClass.getDeclaredField("readBuffer");
                bufferField.setAccessible(true);

                ThreadLocal<ByteBuffer> localBody = (ThreadLocal<ByteBuffer>) bufferField.get(null);

                if (localBody == null) {
                    XposedBridge.log("Couldn't read readBuffer-stream from PoGo.");
                    return;
                }

                ByteBuffer responseBody = localBody.get();
                ByteBuffer dResponseBody = responseBody.duplicate();
                dResponseBody.get(bytes, 0, bodySize);
                HandleResponse(bytes);
            }
        });

    }

    private void HandleResponse(byte[] buffer) {
        ResponseEnvelopeOuterClass.ResponseEnvelope responseEnvelop;
        try {
            responseEnvelop = ResponseEnvelopeOuterClass.ResponseEnvelope.parseFrom(buffer);
        } catch (InvalidProtocolBufferException e) {
            XposedBridge.log("Parsing response failed " + e);
            return;
        }

        long requestId = responseEnvelop.getRequestId();
        if (requestId == 0 || !requestMap.containsKey(requestId)) {
            XposedBridge.log("requestId is 0 or not in requestMap | requestId = " + requestId);
            return;
        }

        // XposedBridge.log("Response " + requestId);
        List<RequestTypeOuterClass.RequestType> requestList = requestMap.get(requestId);

        for (int i = 0; i < requestList.size(); i++) {
            RequestTypeOuterClass.RequestType requestType = requestList.get(i);
            ByteString payload = responseEnvelop.getReturns(i);
            // XposedBridge.log("Response " + requestType.toString());

            if (requestType == RequestTypeOuterClass.RequestType.ENCOUNTER) {
                Encounter(payload);
            }
        }
    }

    private void Encounter(ByteString payload) {

        EncounterResponseOuterClass.EncounterResponse encounterResponse;
        try {
            encounterResponse = EncounterResponseOuterClass.EncounterResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            XposedBridge.log("Parsing EncounterResponse failed " + e);
            return;
        }

        PokemonDataOuterClass.PokemonData encounteredPokemon = encounterResponse.getWildPokemon().getPokemonData();
        CaptureProbabilityOuterClass.CaptureProbability captureProbability = encounterResponse.getCaptureProbability();

        String pokemonName = encounteredPokemon.getPokemonId() + " #" + encounteredPokemon.getPokemonIdValue() + " (CP " + encounteredPokemon.getCp() + ")";
        String pokemonIV = calcPotential(encounteredPokemon) + "% " + "[A/D/S " + encounteredPokemon.getIndividualAttack() + "/" + encounteredPokemon.getIndividualDefense() + "/" + encounteredPokemon.getIndividualStamina() + "]";
        String pokemonIVandProbability = pokemonIV + "\n\n" + "CaptureProbability"
                + "\n" + "Pokéball :\t" + captureProbability.getCaptureProbability(0)
                + "\n" + "Great Ball :\t" + captureProbability.getCaptureProbability(1)
                + "\n" + "Ultra Ball :\t" + captureProbability.getCaptureProbability(2);

        showNotification(pokemonName, pokemonIV, pokemonIVandProbability);
    }

    private double calcPotential(PokemonDataOuterClass.PokemonData encounteredPokemon) {
        return (double) Math.round(((encounteredPokemon.getIndividualAttack() + encounteredPokemon.getIndividualDefense() + encounteredPokemon.getIndividualStamina()) / 45.0 * 100.0) * 10) / 10;
    }

    private void showToast(final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(AndroidAppHelper.currentApplication(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showNotification(final String title, final String text, final String longText) {

        final Context context = AndroidAppHelper.currentApplication();

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context);
                mBuilder.setSmallIcon(android.R.color.background_light);
                mBuilder.setContentTitle(title);
                mBuilder.setContentText(text);
                mBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(longText));
                mBuilder.setVibrate(new long[]{1000});
                mBuilder.setPriority(Notification.PRIORITY_MAX);

                NotificationManager mNotificationManager = (NotificationManager) AndroidAppHelper.currentApplication().getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationManager.notify(699511, mBuilder.build());
            }
        });
    }
}
