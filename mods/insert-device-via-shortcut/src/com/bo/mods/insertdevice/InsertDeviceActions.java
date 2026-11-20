package com.bo.mods.insertdevice;

import bml.actions.ActionProvider;
import bml.actions.Actions;

/**
 * The insert actions. None has a key of its own, so none conflicts with anyone's keys. Each is a real Bitwig action: it is listed in Settings > Shortcuts and the
 * Commander, where the user assigns its key. The IDs (bo_tools_insert_...) come from BO Tools, the
 * mod these actions were first in, and are stored with the user's shortcuts: never change them.
 */
public final class InsertDeviceActions implements ActionProvider {
    @Override
    public void declare(Actions actions) {
        actions.category("insert_device_audio_fx", "Insert Device: Audio FX");
        actions.category("insert_device_instruments", "Insert Device: Instruments");

        // Tool, Chain and Pro-Q 4 first, with the other audio effects
        actions.action("bo_tools_insert_tool", "Insert Tool")
               .category("insert_device_audio_fx")
               .onRun(context -> InsertDevice.insertBitwigDevice(context.widget(), InsertDevice.TOOL, "Tool"));
        actions.action("bo_tools_insert_chain", "Insert Chain")
               .category("insert_device_audio_fx")
               .onRun(context -> InsertDevice.insertBitwigDevice(context.widget(), InsertDevice.CHAIN, "Chain"));
        actions.action("bo_tools_insert_pro_q_4", "Insert Pro-Q 4 (VST3)")
               .category("insert_device_audio_fx")
               .onRun(context -> InsertDevice.insertVst3(context.widget(), InsertDevice.PRO_Q_4, "Pro-Q 4 (VST3)"));
        actions.action("bo_tools_insert_pro_q_4_clap", "Insert Pro-Q 4 (CLAP)")
               .category("insert_device_audio_fx")
               .onRun(context -> InsertDevice.insertProQ4Clap(context.widget()));

        // Every Bitwig audio effect and instrument: no keys of their own, the user assigns them
        declareInserts(actions, BitwigDevices.AUDIO_FX, "insert_device_audio_fx");
        declareInserts(actions, BitwigDevices.INSTRUMENTS, "insert_device_instruments");
    }

    private static void declareInserts(Actions actions, String[] names, String category) {
        for (String name : names) {
            actions.action("bo_tools_insert_" + BitwigDevices.slug(name), "Insert " + name)
                   .category(category)
                   .onRun(context -> InsertDevice.insertLibraryDevice(context.widget(), name));
        }
    }
}
