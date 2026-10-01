package org.matsim.episim.run.modules;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimPerson;
import org.matsim.episim.InfectionEventHandler;
import org.matsim.episim.model.SimulationListener;
import org.matsim.episim.model.VaccinationType;
import org.matsim.episim.model.vaccination.VaccinationModel;
import org.matsim.episim.util.EpisimSplittableRandom;
import org.matsim.facilities.ActivityFacility;
import org.matsim.vehicles.Vehicle;

import java.util.Map;

/**
 * Gives the immunity that the population carries at the start of the run (docs/influenza-multiseasonal.md, decision D17).
 *
 * <p>Before the first day every agent draws, by its age, whether it carries the immunity of the configured product
 * ({@link PriorImmunityConfigGroup}); if it does, the product enters its vaccination history on day 1. The explicit immunity
 * model then applies the curve of the product, so the immunity decays and is combined with later infections like any other
 * event. With no product configured nothing happens and no random number is drawn.</p>
 *
 * <p>A run from a snapshot does not draw again: the state of the persons is restored from the snapshot after this model has
 * been initialised.</p>
 */
public final class PriorImmunityModel implements VaccinationModel {

	private static final Logger log = LogManager.getLogger(PriorImmunityModel.class);

	private final PriorImmunityConfigGroup config;

	@Inject
	public PriorImmunityModel(Config config) {
		this.config = ConfigUtils.addOrGetModule(config, PriorImmunityConfigGroup.class);
	}

	@Override
	public void init(EpisimSplittableRandom rnd, Map<Id<Person>, EpisimPerson> persons,
					 Map<Id<ActivityFacility>, InfectionEventHandler.EpisimFacility> facilities,
					 Map<Id<Vehicle>, InfectionEventHandler.EpisimVehicle> vehicles) {

		VaccinationType product = config.getProduct();
		if (product == null)
			return;

		int given = 0;
		for (EpisimPerson person : persons.values()) {
			if (rnd.nextDouble() < config.shareAt(person.getAge())) {
				vaccinate(person, 1, product);
				given++;
			}
		}
		log.info("Prior immunity: {} of {} agents ({}%) carry '{}'", given, persons.size(),
			String.format("%.1f", 100.0 * given / Math.max(1, persons.size())), product);
	}
}
