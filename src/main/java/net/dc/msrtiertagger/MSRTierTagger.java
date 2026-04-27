package net.dc.msrtiertagger;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side initialiser (runs on both client and dedicated server).
 * MSR Tier Tagger is client-only so this is intentionally minimal.
 * All real logic lives in MSRTierTaggerClient.
 */
public class MSRTierTagger implements ModInitializer {

	public static final String MOD_ID = "msr-tier-tagger";
	public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[MSR Tier Tagger] Loaded.");
	}
}
