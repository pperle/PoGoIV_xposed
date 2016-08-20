package de.chuparch0pper.android.xposed.pogoiv;

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
import POGOProtos.Networking.Responses.CatchPokemonResponseOuterClass;
import POGOProtos.Networking.Responses.DiskEncounterResponseOuterClass;
import POGOProtos.Networking.Responses.EncounterResponseOuterClass;
import POGOProtos.Networking.Responses.IncenseEncounterResponseOuterClass;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

/**
 * entry point for XposedBridge
 * <p/>
 * PoGoIV_xposed would not have been possible without the work of [elfinlazz](https://github.com/elfinlazz).
 * This modul is based on his work on [Pokemon GO IV checker](http://repo.xposed.info/module/de.elfinlazz.android.xposed.pokemongo).
 */
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
                    Helper.Log("Error while getting data, requestBody =" + bodySize + " or bodySize = " + bodySize + " is not valid");
                    return;
                }

                ByteBuffer dRequestBody = requestBody.duplicate();
                byte[] bytes = new byte[bodySize];
                dRequestBody.get(bytes, bodyOffset, bodySize);

                RequestEnvelopeOuterClass.RequestEnvelope requestEnvelop = RequestEnvelopeOuterClass.RequestEnvelope.parseFrom(bytes);
                long requestId = requestEnvelop.getRequestId();

                List<RequestTypeOuterClass.RequestType> requestList = new ArrayList<RequestTypeOuterClass.RequestType>();
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
     * checks buffer for {@link RequestTypeOuterClass.RequestType RequestType} and calls the associated method
     *
     * @param buffer return value of readDataSteam
     */
    private void HandleResponse(byte[] buffer) {
        ResponseEnvelopeOuterClass.ResponseEnvelope responseEnvelop;
        try {
            responseEnvelop = ResponseEnvelopeOuterClass.ResponseEnvelope.parseFrom(buffer);
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
        List<RequestTypeOuterClass.RequestType> requestList = requestMap.get(requestId);

        for (int i = 0; i < requestList.size(); i++) {
            RequestTypeOuterClass.RequestType requestType = requestList.get(i);
            ByteString payload = responseEnvelop.getReturns(i);
            Helper.Log("HandleResponse " + requestType.toString());

            if (requestType == RequestTypeOuterClass.RequestType.ENCOUNTER) {
                Encounter(payload); // wild encounter
            } else if (requestType == RequestTypeOuterClass.RequestType.DISK_ENCOUNTER) {
                DiskEncounter(payload); // lured encounter
            } else if (requestType == RequestTypeOuterClass.RequestType.INCENSE_ENCOUNTER) {
                IncenseEncounter(payload); // incense encounter
            } else if (requestType == RequestTypeOuterClass.RequestType.CATCH_POKEMON) {
                Catch(payload);
            }
        }
    }

    private void Encounter(ByteString payload) {
        EncounterResponseOuterClass.EncounterResponse encounterResponse;
        try {
            encounterResponse = EncounterResponseOuterClass.EncounterResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing EncounterResponse failed " + e);
            return;
        }

        PokemonDataOuterClass.PokemonData encounteredPokemon = encounterResponse.getWildPokemon().getPokemonData();
        CaptureProbabilityOuterClass.CaptureProbability captureProbability = encounterResponse.getCaptureProbability();

        Helper.Log("encounterResponse = ", encounterResponse.getAllFields().entrySet());
        createEncounterNotification(encounteredPokemon, captureProbability);

    }

    private void IncenseEncounter(ByteString payload) {
        IncenseEncounterResponseOuterClass.IncenseEncounterResponse incenseEncounterResponse;
        try {
            incenseEncounterResponse = IncenseEncounterResponseOuterClass.IncenseEncounterResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing IncenseEncounterResponse failed " + e);
            return;
        }

        PokemonDataOuterClass.PokemonData encounteredPokemon = incenseEncounterResponse.getPokemonData();
        CaptureProbabilityOuterClass.CaptureProbability captureProbability = incenseEncounterResponse.getCaptureProbability();

        Helper.Log("IncenseEncounter = ", incenseEncounterResponse.getAllFields().entrySet());
        createEncounterNotification(encounteredPokemon, captureProbability);
    }

    private void DiskEncounter(ByteString payload) {
        DiskEncounterResponseOuterClass.DiskEncounterResponse diskEncounterResponse;
        try {
            diskEncounterResponse = DiskEncounterResponseOuterClass.DiskEncounterResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing DiskEncounterResponse failed " + e);
            return;
        }

        PokemonDataOuterClass.PokemonData encounteredPokemon = diskEncounterResponse.getPokemonData();
        CaptureProbabilityOuterClass.CaptureProbability captureProbability = diskEncounterResponse.getCaptureProbability();

        Helper.Log("DiskEncounterResponse = ", diskEncounterResponse.getAllFields().entrySet());
        createEncounterNotification(encounteredPokemon, captureProbability);
    }

    private void Catch(ByteString payload) {

        CatchPokemonResponseOuterClass.CatchPokemonResponse catchPokemonResponse;
        try {
            catchPokemonResponse = CatchPokemonResponseOuterClass.CatchPokemonResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing CatchPokemonResponse failed " + e);
            return;
        }

        Helper.Log("catchPokemonResponse = ", catchPokemonResponse.getAllFields().entrySet());
        Helper.showToast(catchPokemonResponse.getStatus().toString(), Toast.LENGTH_SHORT);
    }

    private void createEncounterNotification(PokemonDataOuterClass.PokemonData encounteredPokemon, CaptureProbabilityOuterClass.CaptureProbability captureProbability) {
        String pokemonName = encounteredPokemon.getPokemonId() + " (CP " + encounteredPokemon.getCp() + ") LVL " + calcLevel(encounteredPokemon.getCpMultiplier());
        String pokemonIV = calcPotential(encounteredPokemon) + "% " + "[A/D/S " + encounteredPokemon.getIndividualAttack() + "/" + encounteredPokemon.getIndividualDefense() + "/" + encounteredPokemon.getIndividualStamina() + "]";
        String pokemonIVandMoreInfo = pokemonIV
                + "\n\n" + "Moves: " + encounteredPokemon.getMove1() + ", " + encounteredPokemon.getMove2()
                + "\n\n" + "CaptureProbability"
                + "\n" + "Pokéball :\t" + captureProbability.getCaptureProbability(0)
                + "\n" + "Great Ball :\t" + captureProbability.getCaptureProbability(1)
                + "\n" + "Ultra Ball :\t" + captureProbability.getCaptureProbability(2);

        Helper.showNotification(pokemonName, pokemonIV, pokemonIVandMoreInfo);
    }

    private double calcPotential(PokemonDataOuterClass.PokemonData encounteredPokemon) {
        return (double) Math.round(((encounteredPokemon.getIndividualAttack() + encounteredPokemon.getIndividualDefense() + encounteredPokemon.getIndividualStamina()) / 45.0 * 100.0) * 10) / 10;
    }

    private float calcLevel(float cpMultiplier) {
        float level = 1;
        for (double currentCpM : Data.CpM) {
            if (Math.abs(cpMultiplier - currentCpM) < 0.01) {
                return level;
            }
            level += 0.5;
        }
        return level;
    }

}
