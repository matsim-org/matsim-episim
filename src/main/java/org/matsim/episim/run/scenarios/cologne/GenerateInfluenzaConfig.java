package org.matsim.episim.run.scenarios.cologne;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.EpisimPerson;
import org.matsim.episim.PathogenConfigGroup;
import org.matsim.episim.TracingConfigGroup;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.model.Pathogen;
import org.matsim.episim.model.Transition;
import org.matsim.episim.model.TransmissionWeights;
import org.matsim.episim.model.VirusStrain;
import org.matsim.episim.run.legacy.SnzCologneOpenProductionScenario;
import org.matsim.episim.run.modules.InfluenzaCologneScenario;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.matsim.episim.model.Transition.to;
import static org.matsim.episim.run.modules.InfluenzaCologneScenario.SEASON_START;

/**
 * Compiles the influenza parameterisation ({@code docs/influenza-parameterisation.md}) into
 * {@value org.matsim.episim.run.modules.InfluenzaCologneScenario#CONFIG_PATH} and
 * {@value #PROGRESSION_PATH}, the two files {@link InfluenzaCologneScenario} loads at run time.
 * Everything here is plain data once written &mdash; season dates, virus-strain and pathogen
 * parameters, the disease-import schedule, tracing, disease-progression timing &mdash; except the
 * contact-restriction policy, which {@link InfluenzaCologneScenario#buildPolicy()} builds in code
 * (see its javadoc for why); this class calls that same method so the snapshot's policy matches what
 * actually runs.
 *
 * <p>{@code progression.conf} exists as a separate file because
 * {@code EpisimConfigGroup.setProgressionConfig} can only reference an external file from the main
 * XML, not inline data (see its javadoc): a progression table built in Java, as here, has no
 * backing file until we write one out and point {@value org.matsim.episim.run.modules.InfluenzaCologneScenario#CONFIG_PATH}
 * at it. Once that is done, {@link InfluenzaCologneScenario#config()} is a plain
 * {@code ConfigUtils.loadConfig} &mdash; the reference in the XML makes progression load
 * automatically, no custom Java parsing needed.</p>
 *
 * <p>Reuses the Cologne base setup verbatim from {@link SnzCologneOpenProductionScenario} (same
 * Senozon input files, calibration parameter, contact intensities, seasonality) and layers a second
 * {@link Pathogen} (influenza) on top, described purely through {@link VirusStrainConfigGroup} and
 * {@link PathogenConfigGroup}. Every number below is cited. Ported from
 * {@code InfluenzaCologneScenarioTest#configureInfluenza} in matsim-episim-libs, where it was
 * developed and validated before the pathogen config groups were published in an artifact this repo
 * could depend on.</p>
 *
 * <p>Re-run this ({@code mvn compile exec:java -Dexec.mainClass=...GenerateInfluenzaConfig}, or from
 * the IDE) whenever the Cologne base setup or the influenza parameterisation changes &mdash; not for
 * an ordinary parameter tweak; edit {@value #PROGRESSION_PATH} /
 * {@value org.matsim.episim.run.modules.InfluenzaCologneScenario#CONFIG_PATH} directly for that, or
 * {@link InfluenzaCologneScenario#buildPolicy()} for a policy change.</p>
 *
 * <p><b>Known limitations</b> (see {@code docs/influenza-parameterisation.md} for detail): the
 * antibody / individual immunity model is still SARS-CoV-2 specific, so only influenza is seeded
 * (the SARS-CoV-2 disease import is cleared, so only one pathogen circulates). Isolation at symptom
 * onset is set to 75% home isolation, a low-confidence value.</p>
 */
public final class GenerateInfluenzaConfig {

	public static final String PROGRESSION_PATH = "Scenarios/Cologne/progression.conf";

	private static final Pathogen INFLUENZA = new Pathogen("influenza");
	private static final VirusStrain INFLUENZA_STRAIN = VirusStrain.of(INFLUENZA, "influenza");

	/** Weekly NRW influenza notifications 2025/26 that shape the import; provenance in the file header. */
	public static final String IMPORT_DRIVER_PATH = "Scenarios/Cologne/nrw-ifsg-influenza-2025-26.tsv";

	/**
	 * Imported infections per day, in agents of the 25 % sample, per notified NRW case in the same week. Calibration
	 * prior, not an estimate: no evidence found for a Cologne importation rate. Chosen so that import stays small next
	 * to local transmission, otherwise the model would reproduce the NRW curve through its input instead of through
	 * transmission: about 10 agents (40 persons) per day at the NRW peak of ~4000 cases/week, i.e. a few percent of
	 * Cologne's infections if notifications capture 1/20-1/50 of them (an assumption). Check the imported share at the
	 * peak in diseaseImport.tsv after calibration.
	 */
	private static final double IMPORT_PER_NRW_WEEKLY_CASE = 0.0025;

	/** Log-normal sigma of the ICU stay: median 4 d, IQR 1-8 d (doi:10.3390/v17111467); ln(8/1) / (2 * 0.6745). */
	private static final double ICU_LOS_SIGMA = Math.log(8.0 / 1.0) / (2 * 0.6745);

	private GenerateInfluenzaConfig() {
	}

	public static void main(String[] args) throws IOException {

		Config config = new SnzCologneOpenProductionScenario.Builder()
				.setMasks(SnzCologneOpenProductionScenario.Masks.no)
				.build()
				.config();

		configureInfluenza(config);

		config.controller().setOutputDirectory("output/cologne-influenza");

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		// progression can't be inline XML data (see class javadoc); externalise it and point the
		// config at the file, so a plain ConfigUtils.loadConfig picks it up automatically
		String rendered = episimConfig.getProgressionConfig().root()
				.render(ConfigRenderOptions.defaults().setOriginComments(false).setJson(false));
		File progressionFile = new File(PROGRESSION_PATH);
		try (FileWriter writer = new FileWriter(progressionFile)) {
			writer.write(rendered);
		}
		episimConfig.setProgressionConfig(ConfigFactory.parseFile(progressionFile));

		// policy is built in code at run time (InfluenzaCologneScenario#buildPolicy); set it here too
		// so this snapshot reflects what actually runs, even though it is not read back
		episimConfig.setPolicy(InfluenzaCologneScenario.buildPolicy().build());

		ConfigUtils.writeConfig(config, InfluenzaCologneScenario.CONFIG_PATH);
	}

	/**
	 * Layers influenza onto an already-built Cologne {@link Config}: moves the start date to the
	 * 2025/26 season, registers the strain, its pathogen params and its disease progression, and
	 * replaces the SARS-CoV-2 disease import with an influenza import. The source of every number is
	 * cited next to it.
	 */
	static void configureInfluenza(Config config) throws IOException {

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
		VirusStrainConfigGroup virusStrainConfig = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);
		PathogenConfigGroup pathogenConfig = ConfigUtils.addOrGetModule(config, PathogenConfigGroup.class);

		// 0. season window: 2025/26, see InfluenzaCologneScenario.SEASON_START / SEASON_END
		episimConfig.setStartDate(SEASON_START);

		// 1. one pooled strain -> pathogen. Chosen for 2022/23 (sentinel subtyping 95 A(H3N2), 1 A(H1N1)pdm09, 2 B/Victoria
		//    up to KW 44, ARE-Wochenbericht KW 44/2022, doi:10.25646/10757); the 2025/26 subtype mix is not checked yet.
		VirusStrainConfigGroup.StrainParams strain = virusStrainConfig.getOrAddParams(INFLUENZA_STRAIN);
		strain.setPathogen(INFLUENZA);
		// NOT an estimate: calibration placeholder. The target is still the 2022/23 growth rate r = 0.085/day (doubling
		// 8.1 d, ICOSARI flu-SARI KW 45-49/2022, doi:10.5281/zenodo.22686153), i.e. R ~ 1.2-1.35; to be replaced by 2025/26.
		strain.setInfectiousness(1.0);
		// pooled strain = pathogen baseline, no strain-relative severity
		strain.setFactorSeriouslySick(1.0);
		strain.setFactorCritical(1.0);
		// relative susceptibility vs adults >= 40 y for A(H3N2): 12-18 y HR 2.04 (1.19-3.49); < 12 y not significantly
		// elevated -> 1.0; 19-39 y and elderly not reported -> 1.0 (Sauter 2026, doi:10.1038/s41467-026-76037-x)
		strain.setAgeSusceptibility(Map.of(0, 1.0, 11, 1.0, 12, 2.04, 18, 2.04, 19, 1.0));
		// infectivity did not vary with age (Cauchemez 2009, doi:10.1056/NEJMoa0905498)
		strain.setAgeInfectivity(Map.of(0, 1.0));

		// 2. natural history, age-dependent variant (Cologne uses AgeDependentDiseaseStatusTransitionModel).
		//    The seriouslySick values are the effective targets divided by hospitalFactor, which the model multiplies back in.
		double hospitalFactor = episimConfig.getHospitalFactor();
		PathogenConfigGroup.PathogenParams influenza = pathogenConfig.getOrAddParams(INFLUENZA);
		PathogenConfigGroup.ProgressionParams byAge = influenza.getOrAddProgressionParams(true);
		// P(showingSymptoms | contagious): 268 of 478 PCR-confirmed infections symptomatic (PHIRST, Cohen 2021,
		// doi:10.1016/S2214-109X(21)00141-8); no age-stratified estimate found
		byAge.setShowingSymptomsProbabilityByAge(Map.of(0, 0.56));
		// P(seriouslySick | showingSymptoms): flu-associated hospitalisations per symptomatic illness, CDC 2018-19 burden
		// Table 1 (https://archive.cdc.gov/www_cdc_gov/flu/about/burden/2018-2019.html), US substitute for Germany
		byAge.setSeriouslySickProbabilityByAge(Map.of(
				0, 0.00697 / hospitalFactor,     // 0-4 y:   21,046 / 3,018,815
				5, 0.00274 / hospitalFactor,     // 5-17 y:  18,159 / 6,622,851
				18, 0.00561 / hospitalFactor,    // 18-49 y: 54,978 / 9,794,700
				50, 0.01060 / hospitalFactor,    // 50-64 y: 76,617 / 7,224,769
				65, 0.09091 / hospitalFactor));  // 65+ y:   204,326 / 2,247,586
		// P(critical | seriouslySick): ICU share of J09-J11 hospitalisations in Germany 2022/23, 0-17 y 4.4 %, 18-59 y 9.9 %,
		// >= 60 y 11.3 % (InEK data, Meyer 2026, doi:10.1007/s40121-026-01384-7)
		byAge.setCriticalProbabilityByAge(Map.of(0, 0.044, 18, 0.099, 60, 0.113));
		// P(deceased | critical): pooled European ICU mortality 0.24 (0.20-0.27), no age strata
		// (Suarez-Sanchez 2025, doi:10.1111/irv.70073); requires the critical -> deceased transition below
		byAge.setDeathProbabilityByAge(Map.of(0, 0.24));
		// respiratory only: aerosols ~ half of household transmission (Cowling 2013, doi:10.1038/ncomms2922); hand hygiene
		// alone showed no significant effect (Wong 2014, doi:10.1017/S095026881400003X)
		influenza.setRouteTransmissibility(TransmissionWeights.parse("respiratory=1.0"));
		// isolation at symptom onset: illness cut contacts, mostly outside the home, so that R fell to about 1/4
		// (Van Kerckhove 2013, doi:10.1093/aje/kwt196). atHome removes all non-home contacts, so 1 - p = 0.25 -> p = 0.75.
		// Low confidence: ILI cases in England 2009, sicker than all symptomatic infections; no age-specific estimate.
		influenza.setSymptomaticIsolationProbabilityByAge(Map.of(0, 0.75));
		influenza.setSymptomaticIsolationStatus(EpisimPerson.QuarantineStatus.atHome);
		// infectivity by day, read with a signed offset: from symptom onset, or from the middle of the contagious period
		// without symptoms. Placeholder: the COVID curve NormalDistribution(0.5, 2.6) (arXiv:2007.06602) that the infection
		// model used to hard-code, sampled per day and peak-normalised. Its +0.5 d peak offset is defensible for influenza,
		// its width has no influenza evidence (docs/influenza-parameterisation.md, section 3.3); to be fitted so that the
		// model's generation time lands in the 2.2-3.6 d reported for influenza.
		influenza.setInfectivityProfile(sampledNormal(0.5, 2.6, -8, 12));

		// 3. disease progression timing (replaces the COVID progressionConfig of SnzCologneOpenProductionScenario);
		//    externalised to a file after this method returns, see main()
		episimConfig.setProgressionConfig(influenzaProgressionConfig(Transition.config()).build());

		// 4. seasonal inputs. The leisureOutdoorFraction is data (it round-trips fine through XML); the contact-restriction
		//    *policy* is set separately in code, see InfluenzaCologneScenario#buildPolicy and main() above.
		//    The EpisimConfigGroup default pattern (0.8 from mid-April to mid-September, 0.1 from mid-November to
		//    mid-February, linear in between) placed on 2025/26; weather-based fractions would be better
		//    (EpisimUtils.getOutDoorFractionFromDateAndTemp2).
		episimConfig.setLeisureOutdoorFraction(Map.of(
				LocalDate.parse("2025-04-15"), 0.8,
				LocalDate.parse("2025-09-15"), 0.8,
				LocalDate.parse("2025-11-15"), 0.1,
				LocalDate.parse("2026-02-15"), 0.1,
				LocalDate.parse("2026-04-15"), 0.8));
		// no contact tracing for influenza
		ConfigUtils.addOrGetModule(config, TracingConfigGroup.class).setPutTraceablePersonsInQuarantineAfterDay(Integer.MAX_VALUE);

		// 5. seeding: drop the SARS-CoV-2 import; continuous influenza import shaped like the NRW epidemic, see
		//    importSchedule
		episimConfig.getInfections_pers_per_day().clear();
		episimConfig.setInfections_pers_per_day(INFLUENZA_STRAIN,
				importSchedule(readWeeklyCases(Path.of(IMPORT_DRIVER_PATH)), IMPORT_PER_NRW_WEEKLY_CASE));
		// explicit, self-documenting "off" for SARS-CoV-2, on top of EpisimConfigGroup#setInfectionsPerDay now
		// (matsim-episim-libs) correctly dropping a strain entirely when it is absent from the config string:
		// without either, EpisimUtils.findValidEntry(emptyMap, 1, date) falls back to seeding 1 person/day of it,
		// silently violating the "one pathogen per run" assumption.
		episimConfig.setInfections_pers_per_day(VirusStrain.SARS_CoV_2, Map.of(SEASON_START, 0));

		// 6. no antibody parameters on purpose: an influenza infection then induces no antibodies. The antibody-based
		//    severity factor 1 / (1 + ab^beta) is applied even on a first infection, so the former placeholder 5.0 cut
		//    hospitalisation ~6-fold; without antibodies it stays at 1. The Cologne infection model does not read
		//    antibodies for susceptibility.
	}

	/**
	 * Daily import in agents for every day from SEASON_START to SEASON_END, proportional to NRW notifications.
	 *
	 * <ul>
	 *     <li>Each week's cases are the centred three-week mean, which smooths the Christmas dip in reporting
	 *     (2025-W52) and week-to-week noise.</li>
	 *     <li>A week's value applies to each of its days, by reporting week: notifications lag infections by several
	 *     days, so the import is slightly late; not shifted, because no source for the lag was checked.</li>
	 *     <li>Expected imports are fractional; a day gets {@code round(cumulative expected) - round(cumulative before)}
	 *     agents, so the season total matches and low levels come as an agent every few days instead of a
	 *     rounding step.</li>
	 *     <li>Every day gets an entry, zeros included: a value applies until the next entry.</li>
	 * </ul>
	 */
	static Map<LocalDate, Integer> importSchedule(NavigableMap<LocalDate, Integer> weeklyCases, double perWeeklyCase) {
		Map<LocalDate, Integer> schedule = new TreeMap<>();
		double expected = 0;
		long issued = 0;
		for (LocalDate day = SEASON_START; !day.isAfter(InfluenzaCologneScenario.SEASON_END); day = day.plusDays(1)) {
			LocalDate week = day.with(DayOfWeek.MONDAY);
			Integer before = weeklyCases.get(week.minusWeeks(1));
			Integer current = weeklyCases.get(week);
			Integer after = weeklyCases.get(week.plusWeeks(1));
			if (before == null || current == null || after == null)
				throw new IllegalStateException(IMPORT_DRIVER_PATH + " lacks the week of " + week + " or a neighbour of it.");

			expected += perWeeklyCase * (before + current + after) / 3.0;
			long agents = Math.round(expected) - issued;
			issued += agents;
			schedule.put(day, (int) agents);
		}
		return schedule;
	}

	/**
	 * Reads {@code week<TAB>cases} rows (ISO week such as {@code 2025-W39}) keyed by the Monday of the week; lines
	 * starting with {@code #} and the header are skipped.
	 */
	static NavigableMap<LocalDate, Integer> readWeeklyCases(Path file) throws IOException {
		NavigableMap<LocalDate, Integer> cases = new TreeMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#") || line.startsWith("week"))
				continue;
			String[] cols = line.split("\t");
			cases.put(LocalDate.parse(cols[0].trim() + "-1", DateTimeFormatter.ISO_WEEK_DATE), Integer.parseInt(cols[1].trim()));
		}
		return cases;
	}

	/**
	 * A normal density sampled on whole days {@code from..to}, scaled to a peak of 1 and rounded to four decimals.
	 */
	static Map<Integer, Double> sampledNormal(double mean, double sd, int from, int to) {
		Map<Integer, Double> profile = new TreeMap<>();
		for (int day = from; day <= to; day++) {
			double z = (day - mean) / sd;
			profile.put(day, Math.round(Math.exp(-0.5 * z * z) * 1e4) / 1e4);
		}
		return profile;
	}

	/**
	 * Influenza disease-progression timing. Transitions fire once {@code daysSince >= transitionDay}, evaluated once per
	 * day, so everything is discretised to whole days.
	 */
	static Transition.Builder influenzaProgressionConfig(Transition.Builder builder) {

		return builder
				// latent period: shedding rises 0.5-1 d after challenge (Carrat 2008, doi:10.1093/aje/kwm375) -> contagious
				// from the next daily update
				.from(EpisimPerson.DiseaseStatus.infectedButNotContagious,
						to(EpisimPerson.DiseaseStatus.contagious, Transition.fixed(1)))

				// incubation: influenza A median 1.4 d, dispersion 1.51; 1.9 d / 1.22 without an outlier study
				// (Lessler 2009, doi:10.1016/S1473-3099(09)70069-6); minus the 1 d latent step -> median 1.0 d
				// asymptomatic branch: shedding 3-5 (up to 7) d (RKI AGI Saisonbericht 2018/19); median below Carrat's 4.8 d
				// mean because asymptomatic infections shed shorter (Ip 2017, doi:10.1093/cid/ciw841)
				.from(EpisimPerson.DiseaseStatus.contagious,
						to(EpisimPerson.DiseaseStatus.showingSymptoms, Transition.logNormalWithMedianAndSigma(1.0, Math.log(1.51))),
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMedianAndStd(4.0, 2.0)))

				// onset -> admission median 3 d, IQR 1-4 (FluSurv-NET 2011-2019, doi:10.1093/ofid/ofad599); sigma 0.6 matches
				// median and Q3, a log-normal cannot match all three
				// symptomatic illness: shedding and illness end by day 6-7 after onset (Ip 2016, doi:10.1093/cid/civ909);
				// std 2.0 is an assumption
				.from(EpisimPerson.DiseaseStatus.showingSymptoms,
						to(EpisimPerson.DiseaseStatus.seriouslySick, Transition.logNormalWithMedianAndSigma(3.0, 0.6)),
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMedianAndStd(6.0, 2.0)))

				// ward -> ICU: no influenza-specific evidence found; COVID placeholder kept (explicit assumption)
				// hospital stay: mean 5.6 d (SD 6.0), Germany 2022/23 InEK data (Meyer 2026, doi:10.1007/s40121-026-01384-7)
				.from(EpisimPerson.DiseaseStatus.seriouslySick,
						to(EpisimPerson.DiseaseStatus.critical, Transition.logNormalWithMedianAndStd(1.0, 1.0)),
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMeanAndStd(5.6, 6.0)))

				// ICU stay: median 4 d, IQR 1-8 (van der Bie 2025, doi:10.3390/v17111467)
				// time to death in ICU: no evidence found -> same distribution (explicit assumption)
				.from(EpisimPerson.DiseaseStatus.critical,
						to(EpisimPerson.DiseaseStatus.seriouslySickAfterCritical, Transition.logNormalWithMedianAndSigma(4.0, ICU_LOS_SIGMA)),
						to(EpisimPerson.DiseaseStatus.deceased, Transition.logNormalWithMedianAndSigma(4.0, ICU_LOS_SIGMA)))

				// post-ICU ward stay: no influenza-specific evidence found; COVID placeholder kept (explicit assumption)
				.from(EpisimPerson.DiseaseStatus.seriouslySickAfterCritical,
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMedianAndStd(7.0, 7.0)))

				// protection halves only 3.5-7 y after infection (Ranjeva 2019, doi:10.1038/s41467-019-09652-6):
				// no reinfection with the pooled strain within one simulated season
				.from(EpisimPerson.DiseaseStatus.recovered,
						to(EpisimPerson.DiseaseStatus.susceptible, Transition.fixed(365)));
	}

}
