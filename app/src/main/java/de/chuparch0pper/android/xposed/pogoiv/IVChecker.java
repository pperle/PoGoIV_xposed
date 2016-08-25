package de.chuparch0pper.android.xposed.pogoiv;

import android.widget.Toast;

import com.github.aeonlucid.pogoprotos.data.Capture;
import com.github.aeonlucid.pogoprotos.networking.Envelopes;
import com.github.aeonlucid.pogoprotos.networking.Requests;
import com.github.aeonlucid.pogoprotos.networking.Responses;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * entry point for XposedBridge
 * <p/>
 * PoGoIV_xposed would not have been possible without the work of [elfinlazz](https://github.com/elfinlazz).
 * This modul is based on his work on [Pokemon GO IV checker](http://repo.xposed.info/module/de.elfinlazz.android.xposed.pokemongo).
 */
public class IVChecker implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static XSharedPreferences preferences;

    private boolean enableModule;
    private boolean showCaughtToast;
    private boolean showIvNotification;

    private static final Map<Long, List<Requests.RequestType>> requestMap = new HashMap<>();

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        loadSharedPreferences();
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.packageName.equals("com.nianticlabs.pokemongo"))
            return;

        loadSharedPreferences();
        checkIfModuleIsEnabled();

        final Class NiaNetClass = loadPackageParam.classLoader.loadClass("com.nianticlabs.nia.network.NiaNet");

        findAndHookMethod(NiaNetClass, "doSyncRequest", long.class, int.class, String.class, int.class, String.class, ByteBuffer.class, int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ByteBuffer requestBody = (ByteBuffer) param.args[5];
                int bodyOffset = (int) param.args[6];
                int bodySize = (int) param.args[7];

                if (requestBody == null || bodySize <= 0) {
                    Helper.Log("Error while getting data, requestBody =" + bodySize + " or bodySize = " + bodySize + " is not valid");
                    return;
                }

                ByteBuffer dRequestBody = requestBody.duplicate();
                byte[] bytes = new byte[bodySize];
                dRequestBody.get(bytes, bodyOffset, bodySize);

                Envelopes.RequestEnvelope requestEnvelop = Envelopes.RequestEnvelope.parseFrom(bytes);
                long requestId = requestEnvelop.getRequestId();

                List<Requests.RequestType> requestList = new ArrayList<Requests.RequestType>();
                for (int i = 0; i < requestEnvelop.getRequestsCount(); i++) {
                    Helper.Log("doSyncRequest - " + requestEnvelop.getRequests(i).getRequestType().toString());
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
                    Helper.Log("Couldn't read readBuffer-stream from PoGo.");
                    return;
                }

                ByteBuffer responseBody = localBody.get();
                ByteBuffer dResponseBody = responseBody.duplicate();
                dResponseBody.get(bytes, 0, bodySize);
                HandleResponse(bytes);
            }
        });

    }

    /**
     * checks buffer for {@link Envelopes.ResponseEnvelope RequestType} and calls the associated method
     *
     * @param buffer return value of readDataSteam
     */
    private void HandleResponse(byte[] buffer) {
        Envelopes.ResponseEnvelope responseEnvelop;
        try {
            responseEnvelop = Envelopes.ResponseEnvelope.parseFrom(buffer);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing response failed " + e);
            return;
        }

        long requestId = responseEnvelop.getRequestId();
        if (requestId == 0 || !requestMap.containsKey(requestId)) {
            Helper.Log("requestId is 0 or not in requestMap | requestId = " + requestId);
            return;
        }

        Helper.Log("Response " + requestId);
        List<Requests.RequestType> requestList = requestMap.get(requestId);

        for (int i = 0; i < requestList.size(); i++) {
            Requests.RequestType requestType = requestList.get(i);
            ByteString payload = responseEnvelop.getReturns(i);
            Helper.Log("HandleResponse " + requestType.toString());

            Helper.Log("showIvNotification= " + showIvNotification);
            if (showIvNotification) {
                switch (requestType) {
                    case ENCOUNTER:
                        Encounter(payload); // wild encounter
                        break;
                    case DISK_ENCOUNTER:
                        DiskEncounter(payload); // lured encounter
                        break;
                    case INCENSE_ENCOUNTER:
                        IncenseEncounter(payload); // incense encounter
                        break;
                }
            }

            Helper.Log("showCaughtToast= " + showCaughtToast);
            if (showCaughtToast) {
                if (requestType == Requests.RequestType.CATCH_POKEMON) {
                    Catch(payload);
                }
            }
        }
    }

    private void checkIfModuleIsEnabled() {
        if (!enableModule) {
            return;
        }
    }

    private void loadSharedPreferences() {
        // might not work for everyone
        // https://github.com/rovo89/XposedBridge/issues/102
        preferences = new XSharedPreferences(Helper.PACKAGE_NAME);
        preferences.reload();
        boolean worldReadable = preferences.makeWorldReadable();
        Helper.Log("worldReadable = " + worldReadable);

        enableModule = preferences.getBoolean("enable_module", true);
        showIvNotification = preferences.getBoolean("show_iv_notification", true);
        showCaughtToast = preferences.getBoolean("show_caught_toast", true);

        Helper.Log("preferences - enableModule = " + enableModule);
        Helper.Log("preferences - showIvNotification = " + showIvNotification);
        Helper.Log("preferences - showCaughtToast = " + showCaughtToast);
    }

    private void Encounter(ByteString payload) {
        Responses.EncounterResponse encounterResponse;
        try {
            encounterResponse = Responses.EncounterResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing EncounterResponse failed " + e);
            return;
        }

        com.github.aeonlucid.pogoprotos.Data.PokemonData encounteredPokemon = encounterResponse.getWildPokemon().getPokemonData();
        Capture.CaptureProbability captureProbability = encounterResponse.getCaptureProbability();

        Helper.Log("encounterResponse = ", encounterResponse.getAllFields().entrySet());
        createEncounterNotification(encounteredPokemon, captureProbability);

    }

    private void IncenseEncounter(ByteString payload) {
        Responses.IncenseEncounterResponse incenseEncounterResponse;
        try {
            incenseEncounterResponse = Responses.IncenseEncounterResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing IncenseEncounterResponse failed " + e);
            return;
        }

        com.github.aeonlucid.pogoprotos.Data.PokemonData encounteredPokemon = incenseEncounterResponse.getPokemonData();
        Capture.CaptureProbability captureProbability = incenseEncounterResponse.getCaptureProbability();

        Helper.Log("IncenseEncounter = ", incenseEncounterResponse.getAllFields().entrySet());
        createEncounterNotification(encounteredPokemon, captureProbability);
    }

    private void DiskEncounter(ByteString payload) {
        Responses.DiskEncounterResponse diskEncounterResponse;
        try {
            diskEncounterResponse = Responses.DiskEncounterResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing DiskEncounterResponse failed " + e);
            return;
        }

        com.github.aeonlucid.pogoprotos.Data.PokemonData encounteredPokemon = diskEncounterResponse.getPokemonData();
        Capture.CaptureProbability captureProbability = diskEncounterResponse.getCaptureProbability();

        Helper.Log("DiskEncounterResponse = ", diskEncounterResponse.getAllFields().entrySet());
        createEncounterNotification(encounteredPokemon, captureProbability);
    }

    private void Catch(ByteString payload) {

        Responses.CatchPokemonResponse catchPokemonResponse;
        try {
            catchPokemonResponse = Responses.CatchPokemonResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing CatchPokemonResponse failed " + e);
            return;
        }

        Helper.Log("catchPokemonResponse = ", catchPokemonResponse.getAllFields().entrySet());
        Helper.showToast(Helper.getCatchName(catchPokemonResponse.getStatus()), Toast.LENGTH_SHORT);
    }

    private void createEncounterNotification(com.github.aeonlucid.pogoprotos.Data.PokemonData encounteredPokemon, Capture.CaptureProbability captureProbability) {
        String pokemonName = getPokemonName(encounteredPokemon.getPokemonIdValue()) + " (" + Helper.getCpName() + " " + encounteredPokemon.getCp() + ") LVL " + calcLevel(encounteredPokemon.getCpMultiplier());
        String pokemonIV = calcPotential(encounteredPokemon) + "% " + "[A/D/S " + encounteredPokemon.getIndividualAttack() + "/" + encounteredPokemon.getIndividualDefense() + "/" + encounteredPokemon.getIndividualStamina() + "]";
        String pokemonIVandMoreInfo = pokemonIV
                + "\n\n" + "Moves: " + Helper.getPokeMoveName(encounteredPokemon.getMove1()) + ", " + Helper.getPokeMoveName(encounteredPokemon.getMove2())
                + "\n\n" + "CaptureProbability"
                + "\n" + "Pokéball :\t" + getCatchRate(captureProbability, 0, 1) + "%\t (with Razzberry:" + getCatchRate(captureProbability, 0, 1.5) + "%)"
                + "\n" + "Great Ball :\t" + getCatchRate(captureProbability, 1, 1) + "%\t (with Razzberry:" + getCatchRate(captureProbability, 1, 1.5) + "%)"
                + "\n" + "Ultra Ball :\t" + getCatchRate(captureProbability, 2, 1) + "%\t (with Razzberry:" + getCatchRate(captureProbability, 2, 1.5) + "%)";

        Helper.showNotification(pokemonName, pokemonIV, pokemonIVandMoreInfo);
    }

    private double getCatchRate(Capture.CaptureProbability captureProbability, int index, double multiplier) {
        double captureRate = captureProbability.getCaptureProbability(index) * 100 * multiplier;
        return Math.round(captureRate * 100.0) / 100.0;
    }

    private String getPokemonName(int pokemonNumber) {
        return Helper.getPokemonNames()[pokemonNumber - 1];
    }

    private double calcPotential(com.github.aeonlucid.pogoprotos.Data.PokemonData encounteredPokemon) {
        return (double) Math.round(((encounteredPokemon.getIndividualAttack() + encounteredPokemon.getIndividualDefense() + encounteredPokemon.getIndividualStamina()) / 45.0 * 100.0) * 10) / 10;
    }

    private float calcLevel(float cpMultiplier) {
        float level = 1;
        for (double currentCpM : Data.CpM) {
            if (Math.abs(cpMultiplier - currentCpM) < 0.0001) {
                return level;
            }
            level += 0.5;
        }
        return level;
    }

}
