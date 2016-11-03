/*
 * FelicaCard.java
 *
 * Copyright 2011 Eric Butler <eric@codebutler.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.farebot.card.felica;

import android.nfc.Tag;
import android.util.Log;

import com.codebutler.farebot.card.Card;
import com.codebutler.farebot.card.CardRawDataFragmentClass;
import com.codebutler.farebot.card.CardType;
import com.codebutler.farebot.fragment.FelicaCardRawDataFragment;
import com.codebutler.farebot.transit.TransitData;
import com.codebutler.farebot.transit.TransitIdentity;
import com.codebutler.farebot.transit.edy.EdyTransitData;
import com.codebutler.farebot.transit.suica.SuicaTransitData;
import com.codebutler.farebot.util.Utils;

import net.kazzz.felica.FeliCaTag;
import net.kazzz.felica.command.ReadResponse;
import net.kazzz.felica.lib.FeliCaLib;

import org.apache.commons.lang3.ArrayUtils;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Root(name="card")
@CardRawDataFragmentClass(FelicaCardRawDataFragment.class)
public class FelicaCard extends Card {
    private static final String TAG = "FelicaCard";

    @Element(name="idm") private FeliCaLib.IDm mIDm;
    @Element(name="pmm") private FeliCaLib.PMm mPMm;
    @ElementList(name="systems") private List<FelicaSystem> mSystems;

    private FelicaCard() { /* For XML Serializer */ }

    // https://github.com/tmurakam/felicalib/blob/master/src/dump/dump.c
    // https://github.com/tmurakam/felica2money/blob/master/src/card/Suica.cs
    public static FelicaCard dumpTag(byte[] tagId, Tag tag) throws Exception {
        boolean octopusMagic = false;
        FeliCaTag ft = new FeliCaTag(tag);

        FeliCaLib.IDm idm = ft.pollingAndGetIDm(FeliCaLib.SYSTEMCODE_ANY);
        FeliCaLib.PMm pmm = ft.getPMm();

        if (idm == null)
            throw new Exception("Failed to read IDm");

        List<FelicaSystem> systems = new ArrayList<>();

        // FIXME: Enumerate "areas" inside of systems ???
        FeliCaLib.SystemCode[] codes = ft.getSystemCodeList();

        // Check if we failed to get a System Code
        if (codes.length == 0) {
            // Lets try to ping for an Octopus anyway
            FeliCaLib.IDm octopusSystem = ft.pollingAndGetIDm(FeliCaLib.SYSTEMCODE_OCTOPUS);
            if (octopusSystem != null) {
                Log.d(TAG, "Detected octopus card");
                // Octopus has a special knocking sequence to allow unprotected reads, and does not
                // respond to the normal system code listing.

                codes = new FeliCaLib.SystemCode[]{new FeliCaLib.SystemCode(FeliCaLib.SYSTEMCODE_OCTOPUS)};
                octopusMagic = true;
            }
        }

        for (FeliCaLib.SystemCode code : codes) {
            Log.d(TAG, "Got system code: " + Utils.getHexString(code.getBytes()));

            int systemCode = code.getCode();
            //ft.polling(systemCode);

            FeliCaLib.IDm thisIdm = ft.pollingAndGetIDm(systemCode);

            Log.d(TAG, " - Got IDm: " + Utils.getHexString(thisIdm.getBytes()) + "  compare: "
                    + Utils.getHexString(idm.getBytes()));

            byte[] foo = idm.getBytes();
            ArrayUtils.reverse(foo);
            Log.d(TAG, " - Got Card ID? " + Utils.byteArrayToInt(idm.getBytes(), 2, 6) + "  "
                    + Utils.byteArrayToInt(foo, 2, 6));

            Log.d(TAG, " - Got PMm: " + Utils.getHexString(ft.getPMm().getBytes()) + "  compare: "
                    + Utils.getHexString(pmm.getBytes()));

            List<FelicaService> services = new ArrayList<>();
            FeliCaLib.ServiceCode[] serviceCodes;

            if (octopusMagic && code.getCode() == FeliCaLib.SYSTEMCODE_OCTOPUS) {
                Log.d(TAG, "Stuffing in octopus magic service code");
                serviceCodes = new FeliCaLib.ServiceCode[]{new FeliCaLib.ServiceCode(FeliCaLib.SERVICE_OCTOPUS)};
            } else {
                serviceCodes = ft.getServiceCodeList();
            }

            for (FeliCaLib.ServiceCode serviceCode : serviceCodes) {
                byte[] bytes = serviceCode.getBytes();
                ArrayUtils.reverse(bytes);
                int serviceCodeInt = Utils.byteArrayToInt(bytes);
                serviceCode = new FeliCaLib.ServiceCode(serviceCode.getBytes());

                List<FelicaBlock> blocks = new ArrayList<>();

                ft.polling(systemCode);

                byte addr = 0;
                ReadResponse result = ft.readWithoutEncryption(serviceCode, addr);
                while (result != null && result.getStatusFlag1() == 0) {
                    blocks.add(new FelicaBlock(addr, result.getBlockData()));
                    addr++;
                    result = ft.readWithoutEncryption(serviceCode, addr);
                }

                if (blocks.size() > 0) { // Most service codes appear to be empty...
                    FelicaBlock[] blocksArray = blocks.toArray(new FelicaBlock[blocks.size()]);
                    services.add(new FelicaService(serviceCodeInt, blocksArray));
                }
            }

            FelicaService[] servicesArray = services.toArray(new FelicaService[services.size()]);
            systems.add(new FelicaSystem(Utils.byteArrayToInt(code.getBytes()), servicesArray));
        }

        FelicaSystem[] systemsArray = systems.toArray(new FelicaSystem[systems.size()]);
        return new FelicaCard(tagId, new Date(), idm, pmm, systemsArray);
    }

    public FelicaCard(byte[] tagId, Date scannedAt, FeliCaLib.IDm idm, FeliCaLib.PMm pmm, FelicaSystem[] systems) {
        super(CardType.FeliCa, tagId, scannedAt);
        mIDm     = idm;
        mPMm     = pmm;
        mSystems = Utils.arrayAsList(systems);
    }

    public FeliCaLib.IDm getIDm() {
        return mIDm;
    }

    public FeliCaLib.PMm getPMm() {
        return mPMm;
    }

    // FIXME: Getters that parse IDm...

    // date ????
    /*
    public int getManufactureCode() {

    }

    public int getCardIdentification() {

    }

    public int getROMType() {

    }

    public int getICType() {

    }

    public int getTimeout() {

    }
    */

    public List<FelicaSystem> getSystems() {
        return mSystems;
    }

    public FelicaSystem getSystem(int systemCode) {
        for (FelicaSystem system : mSystems) {
            if (system.getCode() == systemCode) {
                return system;
            }
        }
        return null;
    }

    @Override public TransitIdentity parseTransitIdentity() {
        if (SuicaTransitData.check(this))
            return SuicaTransitData.parseTransitIdentity(this);
        else if (EdyTransitData.check(this))
            return EdyTransitData.parseTransitIdentity(this);
        return null;
    }

    @Override public TransitData parseTransitData() {
        Log.d(TAG, "parseTransitData() called!!");
        if (SuicaTransitData.check(this))
            return new SuicaTransitData(this);
        else if (EdyTransitData.check(this))
            return new EdyTransitData(this);
        return null;
    }
}