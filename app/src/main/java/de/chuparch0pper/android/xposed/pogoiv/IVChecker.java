package de.chuparch0pper.android.xposed.pogoiv;

import android.text.Html;
import android.text.format.DateFormat;
import android.widget.Toast;

import com.github.aeonlucid.pogoprotos.Enums;
import com.github.aeonlucid.pogoprotos.Inventory;
import com.github.aeonlucid.pogoprotos.data.Capture;
import com.github.aeonlucid.pogoprotos.data.Gym;
import com.github.aeonlucid.pogoprotos.data.Player;
import com.github.aeonlucid.pogoprotos.inventory.Item;
import com.github.aeonlucid.pogoprotos.map.Fort;
import com.github.aeonlucid.pogoprotos.networking.Envelopes;
import com.github.aeonlucid.pogoprotos.networking.Requests;
import com.github.aeonlucid.pogoprotos.networking.Responses;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

class CountCandyPair implements Comparable<CountCandyPair> {
    private int count;
    private Inventory.Candy candy;

    public CountCandyPair(int count, Inventory.Candy candy) {
        setCount(count);
        setCandy(candy);
    }

    public CountCandyPair(int count, Enums.PokemonFamilyId familyId) {
        this(count, Inventory.Candy.newBuilder().setFamilyId(familyId).setCandy(0).build());
    }

    public CountCandyPair(Inventory.Candy candy) {
        this(0, candy);
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Inventory.Candy getCandy() {
        return candy;
    }

    public void setCandy(Inventory.Candy candy) {
        this.candy = candy == null ? Inventory.Candy.getDefaultInstance() : candy;
    }

    public int incrementCount() {
        return ++count;
    }

    @Override
    public String toString() {
        return Helper.getPokemonName(candy.getFamilyIdValue()) + ": " + count +
               " - Candies: " + candy.getCandy();
    }

    @Override
    public int compareTo(CountCandyPair o) {
        return Comparators.CANDY.compare(this, o);
    }

    public static class Comparators {
        public static Comparator<CountCandyPair> COUNT = new Comparator<CountCandyPair>() {
            @Override
            public int compare(CountCandyPair ccp1, CountCandyPair ccp2) {
                return ccp1.count - ccp2.count;
            }
        };

        public static Comparator<CountCandyPair> CANDY = new Comparator<CountCandyPair>() {
            @Override
            public int compare(CountCandyPair ccp1, CountCandyPair ccp2) {
                return ccp1.candy.getCandy() - ccp2.candy.getCandy();
            }
        };

        public static Comparator<CountCandyPair> FAMILY_ID = new Comparator<CountCandyPair>() {
            @Override
            public int compare(CountCandyPair ccp1, CountCandyPair ccp2) {
                return ccp1.candy.getFamilyIdValue() - ccp2.candy.getFamilyIdValue();
            }
        };
    }
}

class EggHatchRewards {
    private int experienceAwarded;
    private int candyAwarded;
    private int stardustAwarded;

    public EggHatchRewards(int experienceAwarded, int candyAwarded, int stardustAwarded) {
        this.experienceAwarded = experienceAwarded;
        this.candyAwarded = candyAwarded;
        this.stardustAwarded = stardustAwarded;
    }

    public int getExperienceAwarded() {
        return experienceAwarded;
    }

    public int getCandyAwarded() {
        return candyAwarded;
    }

    public int getStardustAwarded() {
        return stardustAwarded;
    }
}

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
    private boolean showGymDetails;
    private boolean showPokestopSpinResults;
    private boolean showStartupNotification;

    private static final Map<Long, List<Requests.RequestType>> requestMap = new HashMap<>();
    private final Map<Long, EggHatchRewards> hatchedEggs = new HashMap<>();
    private final Map<Item.ItemId, Integer> inventoryItems = new EnumMap<>(Item.ItemId.class);
    private int maxItemStorage = 0;

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

        //final Class NiaNetClass = loadPackageParam.classLoader.loadClass("com.nianticlabs.nia.network.NiaNet");

        findAndHookMethod(Helper.getHttpURLConnectionImplName(), loadPackageParam.classLoader,
                "getOutputStream",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {;
                        //HttpURLConnection connection = (HttpURLConnection) param.thisObject;
                        OutputStream outputStream = (OutputStream) param.getResult();
                        WrappedOutputStream wrappedOutputStream =
                                new WrappedOutputStream(outputStream, new IMitmOutputStreamHandler() {
                                    @Override
                                    public void processBytes(byte[] bytes) {
                                        try {

                                            Envelopes.RequestEnvelope requestEnvelop = Envelopes.RequestEnvelope.parseFrom(bytes);
                                            long requestId = requestEnvelop.getRequestId();
                                            Helper.Log("Outgoing request ID - " + requestId);

                                            int matchedRequests = 0;
                                            List<Requests.RequestType> requestList = new ArrayList<>();
                                            for (int i = 0; i < requestEnvelop.getRequestsCount(); i++) {
                                                Requests.Request request = requestEnvelop.getRequests(i);
                                                Helper.Log("getOutputStream - " + request.getRequestType().toString());
                                                requestList.add(request.getRequestType());
                                                if(request.getRequestType() != Requests.RequestType.METHOD_UNSET) {
                                                    matchedRequests++;
                                                }
                                            }

                                            if(matchedRequests > 0) {
                                                requestMap.put(requestId, requestList);
                                            }
                                        } catch (Exception e) {
                                            Helper.Log("Unable to parse OutputStream");
                                        }
                                    }
                                });

                        param.setResult(wrappedOutputStream);
                    }
                }
        );
        findAndHookMethod(Helper.getHttpURLConnectionImplName(), loadPackageParam.classLoader,
                "getInputStream",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (Helper.isNiaNetConnection((URLConnection) param.thisObject)) {
                            InputStream inputStream = (InputStream) param.getResult();

                            WrappedInputStream wrappedInputStream =
                                    new WrappedInputStream(inputStream, new IMitmInputStreamHandler() {
                                        @Override
                                        public void processBytes(byte[] bytes) {
                                            HandleResponse(bytes);
                                        }
                                    });
                            param.setResult(wrappedInputStream);
                        }
                    }

                        /*
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Helper.isNiaNetConnection((URLConnection) param.thisObject)) {
                                requestMap.put(++requestId, (InputStream) param.getResult());
                            }
                        }
                        */
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

            Helper.Log("showGymDetails= " + showGymDetails);
            if (showGymDetails) {
                if (requestType == Requests.RequestType.GET_GYM_DETAILS) {
                    GetGymDetails(payload);
                }
            }

            Helper.Log("showPokestopSpinResults= " + showPokestopSpinResults);
            if (showPokestopSpinResults) {
                if (requestType == Requests.RequestType.FORT_SEARCH) {
                    FortSearch(payload);
                }
            }

            if (requestType == Requests.RequestType.GET_HATCHED_EGGS) {
                GetHatchedEggs(payload);
            }

            if (requestType == Requests.RequestType.GET_PLAYER) {
                GetPlayer(payload);
            }

            if (requestType == Requests.RequestType.GET_INVENTORY) {
                GetInventory(payload);
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
        showGymDetails = preferences.getBoolean("show_gym_details", true);
        showPokestopSpinResults = preferences.getBoolean("show_pokestop_spin_results", true);
        showStartupNotification = preferences.getBoolean("show_startup_notification", false);

        Helper.Log("preferences - enableModule = " + enableModule);
        Helper.Log("preferences - showIvNotification = " + showIvNotification);
        Helper.Log("preferences - showCaughtToast = " + showCaughtToast);
        Helper.Log("preferences - showGymDetails = " + showGymDetails);
        Helper.Log("preferences - showPokestopSpinResults = " + showPokestopSpinResults);
        Helper.Log("preferences - showStartupNotification = " + showStartupNotification);
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

        // Helper.Log("encounterResponse = ", encounterResponse.getAllFields().entrySet());
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

        // Helper.Log("IncenseEncounter = ", incenseEncounterResponse.getAllFields().entrySet());
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

        // Helper.Log("DiskEncounterResponse = ", diskEncounterResponse.getAllFields().entrySet());
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

        // Helper.Log("catchPokemonResponse = ", catchPokemonResponse.getAllFields().entrySet());

        String catchMessage = Helper.getCatchName(catchPokemonResponse.getStatus());
        double missPercent = catchPokemonResponse.getMissPercent();
        if (missPercent != 0D)
            catchMessage += " (" + (Math.round(missPercent * 10000) / 100D) + "%)";

        Helper.showToast(catchMessage, Toast.LENGTH_SHORT);
    }

    private void GetGymDetails(ByteString payload) {
        Responses.GetGymDetailsResponse getGymDetailsResponse;
        try {
            getGymDetailsResponse = Responses.GetGymDetailsResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing GetGymDetailsResponse failed " + e);
            return;
        }

        // Helper.Log("getGymDetailsResponse = ", getGymDetailsResponse.getAllFields().entrySet());

        if (!getGymDetailsResponse.hasGymState())
            return;

        if (getGymDetailsResponse.getResult() != Responses.GetGymDetailsResponse.Result.SUCCESS) {
            Helper.showToast(Html.fromHtml("Error getting gym details: <b>" + Helper.getGenericEnumName(getGymDetailsResponse.getResult()) + "</b>"), Toast.LENGTH_LONG);
            return;
        }

        final Gym.GymState gymState = getGymDetailsResponse.getGymState();

        Enums.TeamColor ownedByTeam = Enums.TeamColor.UNRECOGNIZED;
        long prestige = -1;
        if (gymState.hasFortData()) {
            Fort.FortData fortData = gymState.getFortData();
            ownedByTeam = fortData.getOwnedByTeam();
            prestige = fortData.getGymPoints();
        }

        final String title = "GYM: " + getGymDetailsResponse.getName();

        final StringBuilder summary = new StringBuilder(64);
        summary.append("Prestige: ").append(prestige).append(" | ");

        switch (ownedByTeam) {
            case NEUTRAL:
                summary.append("Neutral Gym");
                break;

            case BLUE:
                summary.append("Team Mystic");
                break;

            case RED:
                summary.append("Team Valor");
                break;

            case YELLOW:
                summary.append("Team Instinct");
                break;

            default:
                summary.append("(Unknown color)");
                break;
        }

        final int membershipsCount = gymState.getMembershipsCount();
        summary.append(" | ").append(membershipsCount).append(" Defenders");

        StringBuilder longText;

        if (membershipsCount > 0) {
            longText = new StringBuilder(1024);

            for (Gym.GymMembership membership : gymState.getMembershipsList()) {
                final Player.PlayerPublicProfile trainer = membership.getTrainerPublicProfile();
                final com.github.aeonlucid.pogoprotos.Data.PokemonData pokemonData = membership.getPokemonData();

                if (longText.length() > 0)
                    longText.append('\n');
                longText.append(Helper.getPokemonName(pokemonData.getPokemonIdValue()));

                if (pokemonData.getFavorite() == 1)
                    longText.append(" ★");

                String nickname = pokemonData.getNickname();
                if (!nickname.isEmpty())
                    longText.append(" [").append(nickname).append("]");

                longText.append(" | ").append(Helper.getCpName()).append(" ").append(pokemonData.getCp());
                longText.append(" | L. ").append(calcLevel(pokemonData.getCpMultiplier() + pokemonData.getAdditionalCpMultiplier()));
                longText.append(" | HP ").append(pokemonData.getStaminaMax());
                longText.append("\nA. ").append(pokemonData.getBattlesAttacked());
                longText.append(", D. ").append(pokemonData.getBattlesDefended());
                longText.append(" | Tr.: ").append(trainer.getName());
                longText.append(", L. ").append(trainer.getLevel());
                longText.append("\nIVs: ").append(calcPotential(pokemonData));
                longText.append("% | ").append(pokemonData.getIndividualAttack());
                longText.append("/").append(pokemonData.getIndividualDefense());
                longText.append("/").append(pokemonData.getIndividualStamina());
                longText.append("\nMoves: ").append(Helper.getPokeMoveName(pokemonData.getMove1()));
                longText.append(", ").append(Helper.getPokeMoveName(pokemonData.getMove2()));
            }
        }
        else {
            longText = new StringBuilder("No defending Pokémon.");
        }

        Helper.showNotification(title, summary.toString(), longText.toString());
    }

    private void FortSearch(ByteString payload) {
        Responses.FortSearchResponse fortSearchResponse;
        try {
            fortSearchResponse = Responses.FortSearchResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing FortSearchResponse failed " + e);
            return;
        }

        // Helper.Log("fortSearchResponse = ", fortSearchResponse.getAllFields().entrySet());

        int itemsAwardedCount = fortSearchResponse.getItemsAwardedCount();
        int experienceAwarded = fortSearchResponse.getExperienceAwarded();

        if (fortSearchResponse.getResult() != Responses.FortSearchResponse.Result.SUCCESS) {
            Helper.showToast(Html.fromHtml("Error spinning Pokéstop: <b>" + Helper.getGenericEnumName(fortSearchResponse.getResult()) + "</b>"), Toast.LENGTH_LONG);
            if (itemsAwardedCount == 0 && experienceAwarded == 0)
                return;
        }

        if (itemsAwardedCount > 0) {
            int newItemCount = getItemCount() + itemsAwardedCount;
            String toastText = "You now have <b>" + newItemCount + "/" + maxItemStorage + "</b> items";
            if (maxItemStorage > 0) {
                int slotsLeft = maxItemStorage - newItemCount;
                if (slotsLeft <= 0)
                    toastText += "<br><i><b>WARNING</b>: Bag is FULL!</i>";
                else if (slotsLeft <= 10)
                    toastText += "<br><i>WARNING: Bag almost full! <b>" + slotsLeft + "</b> slots left</i>";
            }
            Helper.showToast(Html.fromHtml(toastText), Toast.LENGTH_LONG);
        }

        final String title = "Pokéstop: Got " + itemsAwardedCount + " items and " + experienceAwarded + " XP";

        final StringBuilder summary = new StringBuilder(64);
        Date cooldownComplete = new Date(fortSearchResponse.getCooldownCompleteTimestampMs());
        //summary.append("Cooldown end: ").append(java.text.DateFormat.getTimeInstance(java.text.DateFormat.DEFAULT, Locale.getDefault()).format(cooldownComplete));
        summary.append("Wait until ").append(DateFormat.getTimeFormat(Helper.getPokeContext()).format(cooldownComplete));
        summary.append(" | Chain hack seq. n.: ").append(fortSearchResponse.getChainHackSequenceNumber());

        int gemsAwarded = fortSearchResponse.getGemsAwarded();
        if (gemsAwarded != 0)
            summary.append(" | Gems: ").append(gemsAwarded);

        final StringBuilder longText = new StringBuilder(512);
        longText.append(summary).append("\nItems:");

        Map<Item.ItemId, Integer> itemMap = new EnumMap<>(Item.ItemId.class);
        for (Item.ItemAward item : fortSearchResponse.getItemsAwardedList()) {
            Item.ItemId itemId = item.getItemId();
            int itemCount = item.getItemCount();

            if (itemMap.containsKey(itemId))
                itemMap.put(itemId, itemMap.get(itemId) + itemCount);
            else
                itemMap.put(itemId, itemCount);
        }

        for (Map.Entry<Item.ItemId, Integer> entry : itemMap.entrySet()) {
            longText.append("\n  • \t");
            longText.append(Helper.getItemName(entry.getKey(), entry.getValue()));
        }

        if (fortSearchResponse.hasPokemonDataEgg()) {
            com.github.aeonlucid.pogoprotos.Data.PokemonData eggData = fortSearchResponse.getPokemonDataEgg();
            longText.append("\n\uD83D\uDC23\tEgg (").append(eggData.getEggKmWalkedTarget()).append(" km)");
        }

        Helper.showNotification(title, summary.toString(), longText.toString());
    }

    private void GetHatchedEggs(ByteString payload) {
        Responses.GetHatchedEggsResponse getHatchedEggsResponse;
        try {
            getHatchedEggsResponse = Responses.GetHatchedEggsResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing getHatchedEggsResponse failed " + e);
            return;
        }

        // Helper.Log("getHatchedEggsResponse = ", getHatchedEggsResponse.getAllFields().entrySet());

        if (!getHatchedEggsResponse.getSuccess())
            return;

        for (int i = 0, l = getHatchedEggsResponse.getPokemonIdCount(); i < l; i++) {
            EggHatchRewards eggHatchRewards = new EggHatchRewards(getHatchedEggsResponse.getExperienceAwarded(i),
                                                                  getHatchedEggsResponse.getCandyAwarded(i),
                                                                  getHatchedEggsResponse.getStardustAwarded(i));
            hatchedEggs.put(getHatchedEggsResponse.getPokemonId(i), eggHatchRewards);
        }
    }

    private void GetPlayer(ByteString payload) {
        Responses.GetPlayerResponse getPlayerResponse;
        try {
            getPlayerResponse = Responses.GetPlayerResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing getPlayerResponse failed " + e);
            return;
        }

        // Helper.Log("getPlayerResponse = ", getPlayerResponse.getAllFields().entrySet());

        if (!getPlayerResponse.getSuccess() || !getPlayerResponse.hasPlayerData())
            return;

        com.github.aeonlucid.pogoprotos.Data.PlayerData playerData = getPlayerResponse.getPlayerData();

        maxItemStorage = playerData.getMaxItemStorage();
    }

    private void GetInventory(ByteString payload) {
        Responses.GetInventoryResponse getInventoryResponse;
        try {
            getInventoryResponse = Responses.GetInventoryResponse.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            Helper.Log("Parsing getInventoryResponse failed " + e);
            return;
        }

        // Helper.Log("getInventoryResponse = ", getInventoryResponse.getAllFields().entrySet());

        if (!getInventoryResponse.getSuccess() || !getInventoryResponse.hasInventoryDelta())
            return;

        Inventory.InventoryDelta delta = getInventoryResponse.getInventoryDelta();

        if (delta.getOriginalTimestampMs() == 0L) {
            // First GetInventory request, server sends the full inventory
            Map<Enums.PokemonFamilyId, CountCandyPair> pokemonMap = new EnumMap<>(Enums.PokemonFamilyId.class);

            for (Inventory.InventoryItem inventoryItem : delta.getInventoryItemsList()) {
                if (!inventoryItem.hasInventoryItemData())
                    continue;
                Inventory.InventoryItemData inventoryItemData = inventoryItem.getInventoryItemData();

                if (showStartupNotification) {
                    if (inventoryItemData.hasPokemonData()) {
                        com.github.aeonlucid.pogoprotos.Data.PokemonData pokemonData = inventoryItemData.getPokemonData();
                        if (pokemonData.getIsEgg())
                            continue;
                        Enums.PokemonFamilyId familyId = Enums.PokemonFamilyId.forNumber(pokemonData.getPokemonIdValue());
                        if (familyId != null && familyId != Enums.PokemonFamilyId.FAMILY_HYPNO) {
                            if (pokemonMap.containsKey(familyId)) {
                                pokemonMap.get(familyId).incrementCount();
                            }
                            else {
                                pokemonMap.put(familyId, new CountCandyPair(1, familyId));
                            }
                        }
                    }

                    if (inventoryItemData.hasCandy()) {
                        Inventory.Candy candy = inventoryItemData.getCandy();
                        Enums.PokemonFamilyId familyId = candy.getFamilyId();
                        if (pokemonMap.containsKey(familyId)) {
                            pokemonMap.get(familyId).setCandy(candy);
                        }
                        else {
                            pokemonMap.put(familyId, new CountCandyPair(candy));
                        }
                    }
                }

                if (inventoryItemData.hasItem()) {
                    Item.ItemData itemData = inventoryItemData.getItem();
                    inventoryItems.put(itemData.getItemId(), itemData.getCount());
                }
            }

            if (showStartupNotification) {
                final String title = "Base Pokémon summary";
                final String summary;
                final StringBuilder longText = new StringBuilder(512);

                List<CountCandyPair> pokemonList = new LinkedList<>(pokemonMap.values());
                Collections.sort(pokemonList, Collections.reverseOrder());

                int pokemonCountTotal = 0;
                int candyCountTotal = 0;
                for (CountCandyPair ccp : pokemonList) {
                    if (longText.length() > 0)
                        longText.append('\n');
                    longText.append(ccp.toString());
                    pokemonCountTotal += ccp.getCount();
                    candyCountTotal += ccp.getCandy().getCandy();
                }

                summary = pokemonCountTotal + " base Pokémon, " + candyCountTotal + " Candies";

                Helper.showNotification(title, summary, longText.toString());
            }
        }
        else {
            // Server only sends what changed since the last request
            final String title;
            final String summary;
            final StringBuilder longText = new StringBuilder(512);
            int eggsHatched = 0;
            int experienceAwardedTotal = 0;
            int stardustAwardedTotal = 0;
            String lastHatchedPokemonName = "";

            for (Inventory.InventoryItem inventoryItem : delta.getInventoryItemsList()) {
                if (!inventoryItem.hasInventoryItemData())
                    continue;
                Inventory.InventoryItemData inventoryItemData = inventoryItem.getInventoryItemData();

                if (showIvNotification && inventoryItemData.hasPokemonData()) {
                    com.github.aeonlucid.pogoprotos.Data.PokemonData pokemonData = inventoryItemData.getPokemonData();

                    long pokemonId = pokemonData.getId();

                    if (hatchedEggs.containsKey(pokemonId)) {
                        EggHatchRewards eggHatchRewards = hatchedEggs.get(pokemonId);
                        hatchedEggs.remove(pokemonId);

                        int experienceAwarded = eggHatchRewards.getExperienceAwarded();
                        int candyAwarded = eggHatchRewards.getCandyAwarded();
                        int stardustAwarded = eggHatchRewards.getStardustAwarded();

                        eggsHatched++;
                        experienceAwardedTotal += experienceAwarded;
                        stardustAwardedTotal += stardustAwarded;
                        lastHatchedPokemonName = Helper.getPokemonName(pokemonData.getPokemonIdValue());

                        if (longText.length() > 0)
                            longText.append('\n');
                        longText.append(lastHatchedPokemonName).append(" \uD83D\uDC23 ");
                        longText.append(experienceAwarded).append(" XP | ");
                        longText.append(candyAwarded).append(" Cd. | ");
                        longText.append(stardustAwarded).append(" SD.\n");
                        longText.append("L. ").append(calcLevel(pokemonData));
                        longText.append(" | IVs: ").append(calcPotential(pokemonData));
                        longText.append("% | ").append(pokemonData.getIndividualAttack());
                        longText.append("/").append(pokemonData.getIndividualDefense());
                        longText.append("/").append(pokemonData.getIndividualStamina());
                    }
                }

                if (inventoryItemData.hasItem()) {
                    Item.ItemData itemData = inventoryItemData.getItem();
                    inventoryItems.put(itemData.getItemId(), itemData.getCount());
                }
            }

            if (eggsHatched > 0) {
                if (eggsHatched > 1)
                    title = "Eggs hatched: " + eggsHatched;
                else
                    title = "Egg hatched: " + lastHatchedPokemonName;

                summary = eggsHatched + " egg" + (eggsHatched > 1 ? "s" : "") + " hatched, got " +
                          experienceAwardedTotal + " XP, " + stardustAwardedTotal + " Stardust";

                Helper.showNotification(title, summary, longText.toString());
            }
        }
    }

    private static void createEncounterNotification(com.github.aeonlucid.pogoprotos.Data.PokemonData encounteredPokemon, Capture.CaptureProbability captureProbability) {
        String pokemonName = Helper.getPokemonName(encounteredPokemon.getPokemonIdValue()) + " (" + Helper.getCpName() + " " + encounteredPokemon.getCp() + ") LVL " + calcLevel(encounteredPokemon);
        String pokemonIV = calcPotential(encounteredPokemon) + "% " + "[A/D/S " + encounteredPokemon.getIndividualAttack() + "/" + encounteredPokemon.getIndividualDefense() + "/" + encounteredPokemon.getIndividualStamina() + "]";
        String pokemonIVandMoreInfo = pokemonIV
                + "\n\n" + "Moves: " + Helper.getPokeMoveName(encounteredPokemon.getMove1()) + ", " + Helper.getPokeMoveName(encounteredPokemon.getMove2())
                + "\n\n" + "Capture Probability:"
                + "\n" + "Poké Ball:\t" +  getCatchRate(captureProbability, 0, 1) + "%\t (" + captureProbability.getCaptureProbability(0) + ")"
                + "\n" + "Great Ball:\t" + getCatchRate(captureProbability, 1, 1) + "%\t (" + captureProbability.getCaptureProbability(1) + ")"
                + "\n" + "Ultra Ball:\t" + getCatchRate(captureProbability, 2, 1) + "%\t (" + captureProbability.getCaptureProbability(2) + ")";

                /* We still don't know how Razz Berries affect catch rate exactly, and how the x1.5 modifier is used in the formula
                + "\n" + "Pokéball :\t" + getCatchRate(captureProbability, 0, 1) + "%\t (with Razzberry:" + getCatchRate(captureProbability, 0, 1.5) + "%)"
                + "\n" + "Great Ball :\t" + getCatchRate(captureProbability, 1, 1) + "%\t (with Razzberry:" + getCatchRate(captureProbability, 1, 1.5) + "%)"
                + "\n" + "Ultra Ball :\t" + getCatchRate(captureProbability, 2, 1) + "%\t (with Razzberry:" + getCatchRate(captureProbability, 2, 1.5) + "%)";
                */

        Helper.showNotification(pokemonName, pokemonIV, pokemonIVandMoreInfo);

        if (Helper.isBubblestratPokemon(encounteredPokemon.getPokemonIdValue(), calcLevel(encounteredPokemon), encounteredPokemon.getMove1Value())) {
            Helper.showToast("This pokémon is a compatible Bubblestrat defender!", Toast.LENGTH_SHORT);
        }
    }

    private static double getCatchRate(Capture.CaptureProbability captureProbability, int index, double multiplier) {
        double captureRate = captureProbability.getCaptureProbability(index) * 100d * multiplier;
        if (captureRate > 100.0d) {
            captureRate = 100.0d;
        }
        return Math.round(captureRate * 100.0d) / 100.0d;
    }

    private static double calcPotential(com.github.aeonlucid.pogoprotos.Data.PokemonData encounteredPokemon) {
        return (double) Math.round(((encounteredPokemon.getIndividualAttack() + encounteredPokemon.getIndividualDefense() + encounteredPokemon.getIndividualStamina()) / 45.0 * 100.0) * 10) / 10;
    }

    private static float calcLevel(float cpMultiplier) {
        float level = 1;
        for (double currentCpM : Data.CpM) {
            if (Math.abs(cpMultiplier - currentCpM) < 0.0001) {
                return level;
            }
            level += 0.5;
        }
        return level;
    }

    private static float calcLevel(com.github.aeonlucid.pogoprotos.Data.PokemonData pokemonData) {
        if (pokemonData.getIsEgg())
            return 0;
        return calcLevel(pokemonData.getCpMultiplier() + pokemonData.getAdditionalCpMultiplier());
    }

    private int getItemCount() {
        int itemCount = 1; // Player Camera uses 1 item slot
        for (Map.Entry<Item.ItemId, Integer> entry : inventoryItems.entrySet()) {
            itemCount += entry.getValue();
        }
        return itemCount;
    }
}
