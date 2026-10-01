package org.matsim.episim.run.scenarios;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimPerson.DiseaseStatus;
import org.matsim.episim.ImmunityConfigGroup;
import org.matsim.episim.VaccinationConfigGroup;
import org.matsim.episim.model.VaccinationType;
import org.matsim.episim.run.modules.PriorImmunityConfigGroup;
import org.matsim.episim.ProtectionCurve;
import org.matsim.episim.model.VirusStrain;
import org.matsim.episim.run.scenarios.InfluenzaParameterisation.StrainSpec;

import java.util.List;
import java.util.Map;

/**
 * The {@code immunity} group of the multi-season influenza scenario (docs/influenza-multiseasonal.md, section 3; decisions
 * D2, D4; assumptions A3 to A5, A10 to A16).
 *
 * <p>For every source strain (an infection), every target strain and every target status a curve of the protection over the
 * days since the infection, from the rule</p>
 *
 * <pre>protection(S -> T, status, day) = r(S, T, status) * H(S)(day)</pre>
 *
 * <p>{@code H} is the protection after a natural infection with a strain of the subtype of {@code S}: the peak, which then
 * halves every half-life of the subtype. {@code r} is the relation of the two strains, in [0, 1]. The curve is written at
 * the days {@link #CURVE_DAYS}, rounded to four decimals; after the last point the value is held, and the last point lies
 * beyond the end of the run.</p>
 *
 * <p>Only protection against infection and against showing symptoms is modelled; protection against the later statuses is
 * zero (A14). Everything here is a placeholder unless the decision log names a source.</p>
 */
public final class MultiSeasonImmunity {

	/** Days since the infection at which a curve has a point; the last one is beyond the end of the run (1,330 days). */
	public static final int[] CURVE_DAYS = {0, 365, 730, 1095, 1460};

	/**
	 * The product of the earlier B/Victoria exposure of the population (D17): not a vaccine of the run, but the way to give agents
	 * immunity on the first day; see {@code PriorImmunityModel}.
	 */
	public static final VaccinationType PRIOR_B = VaccinationType.of("priorB");

	/** Statuses against which the model protects (A14): infection and showing symptoms. */
	private static final List<DiseaseStatus> PROTECTED = List.of(DiseaseStatus.infectedButNotContagious, DiseaseStatus.showingSymptoms);

	/** The A(H3N2) strains, the A(H1N1)pdm09 strains and B/Victoria: the half-life and the relation depend on the lineage. */
	private enum Lineage {
		H3N2, H1N1PDM09, BVIC
	}

	/**
	 * The parameters of the rule; {@link #defaults()} are the values of the decision log, the others are the sensitivity cases.
	 *
	 * @param peak                     {@code P0}, protection against infection right after an infection (A11)
	 * @param halfLifeH3n2Years        half-life of the protection after an A(H3N2) infection (Ranjeva 2019, adults)
	 * @param halfLifeH1n1Years        half-life after an A(H1N1)pdm09 infection (Ranjeva 2019, adults)
	 * @param halfLifeBvicYears        half-life after a B/Victoria infection, taken equal to A(H1N1)pdm09 (A12)
	 * @param driftK                   {@code r} between {@code H3N2_2022_23} and subclade K, both directions (A5)
	 * @param h1n1Pair                 {@code r} between the two A(H1N1)pdm09 strains, both directions (A3)
	 * @param heterosubtypicSymptoms   {@code r} for protection against showing symptoms between A(H3N2) and A(H1N1)pdm09, all
	 *                                 pairs and both directions; zero in the base run (D2)
	 * @param combinator               how the protection of several infections of one person is combined (A15)
	 * @param priorBMaxShare           share of the agents of age {@code priorBRampAge} and older who carry B/Victoria immunity on
	 *                                 the first day; the share rises linearly from 0 at age 0 (D17, A39); 0 switches it off
	 * @param priorBRampAge            age at which the share reaches its maximum
	 * @param priorBYears              years between the earlier B/Victoria exposure and the first day; the protection of the
	 *                                 product starts at what an infection that long ago has left
	 */
	public record Parameters(double peak, double halfLifeH3n2Years, double halfLifeH1n1Years, double halfLifeBvicYears,
							 double driftK, double h1n1Pair, double heterosubtypicSymptoms,
							 ImmunityConfigGroup.Combinator combinator, double priorBMaxShare, double priorBRampAge,
							 double priorBYears) {

		public static Parameters defaults() {
			return new Parameters(0.90, 4.1, 6.4, 6.4, 0.4, 1.0, 0.0, ImmunityConfigGroup.Combinator.min, 0.6, 25.0, 3.0);
		}

		public Parameters withPeak(double peak) {
			return new Parameters(peak, halfLifeH3n2Years, halfLifeH1n1Years, halfLifeBvicYears, driftK, h1n1Pair,
					heterosubtypicSymptoms, combinator, priorBMaxShare, priorBRampAge, priorBYears);
		}

		public Parameters withDriftK(double driftK) {
			return new Parameters(peak, halfLifeH3n2Years, halfLifeH1n1Years, halfLifeBvicYears, driftK, h1n1Pair,
					heterosubtypicSymptoms, combinator, priorBMaxShare, priorBRampAge, priorBYears);
		}

		public Parameters withH1n1Pair(double h1n1Pair) {
			return new Parameters(peak, halfLifeH3n2Years, halfLifeH1n1Years, halfLifeBvicYears, driftK, h1n1Pair,
					heterosubtypicSymptoms, combinator, priorBMaxShare, priorBRampAge, priorBYears);
		}

		public Parameters withHeterosubtypicSymptoms(double heterosubtypicSymptoms) {
			return new Parameters(peak, halfLifeH3n2Years, halfLifeH1n1Years, halfLifeBvicYears, driftK, h1n1Pair,
					heterosubtypicSymptoms, combinator, priorBMaxShare, priorBRampAge, priorBYears);
		}

		public Parameters withCombinator(ImmunityConfigGroup.Combinator combinator) {
			return new Parameters(peak, halfLifeH3n2Years, halfLifeH1n1Years, halfLifeBvicYears, driftK, h1n1Pair,
					heterosubtypicSymptoms, combinator, priorBMaxShare, priorBRampAge, priorBYears);
		}

		/** All three half-lives multiplied by {@code factor}; the prior immunity of B (A39) keeps its three years. */
		public Parameters withHalfLifeScale(double factor) {
			return new Parameters(peak, halfLifeH3n2Years * factor, halfLifeH1n1Years * factor, halfLifeBvicYears * factor, driftK,
					h1n1Pair, heterosubtypicSymptoms, combinator, priorBMaxShare, priorBRampAge, priorBYears);
		}

		/** The share of the oldest agents who carry B/Victoria immunity at the start; 0 switches the prior immunity off. */
		public Parameters withPriorBMaxShare(double priorBMaxShare) {
			return new Parameters(peak, halfLifeH3n2Years, halfLifeH1n1Years, halfLifeBvicYears, driftK, h1n1Pair,
					heterosubtypicSymptoms, combinator, priorBMaxShare, priorBRampAge, priorBYears);
		}
	}

	private MultiSeasonImmunity() {
	}

	/**
	 * Adds the {@code immunity} group with the explicit model, the combinator, no protection against other pathogens, and a
	 * source for each of the five strains with a curve for every target strain and every supported target status.
	 */
	public static void configure(Config config, Parameters parameters) {

		ImmunityConfigGroup immunity = ConfigUtils.addOrGetModule(config, ImmunityConfigGroup.class);
		immunity.setModel(ImmunityConfigGroup.Model.explicit);
		immunity.setCombinator(parameters.combinator());
		immunity.setOtherPathogensProtection(0.0);

		for (StrainSpec source : MultiSeasonStrains.all()) {
			ImmunityConfigGroup.SourceParams params = immunity.getOrAddSource(source.strain());
			for (StrainSpec target : MultiSeasonStrains.all())
				for (DiseaseStatus status : ImmunityConfigGroup.SUPPORTED_TARGETS)
					params.setProtection(status, target.strain(), curve(source.strain(), target.strain(), status, parameters));
		}

		if (parameters.priorBMaxShare() > 0.0)
			configurePriorB(config, immunity, parameters);
	}

	/**
	 * The immunity to B/Victoria that the population carries at the start (D17): a product with a curve against {@code BVic}, a
	 * neutral entry of that product in the vaccination group, and the {@code priorImmunity} group that says who gets it.
	 *
	 * <p>The entry in the vaccination group is needed because the infection model reads the infectivity of every vaccinated
	 * infector, and a strain without an entry falls back to the SARS-CoV-2 values: it is set to 1.0 for every strain.</p>
	 */
	private static void configurePriorB(Config config, ImmunityConfigGroup immunity, Parameters parameters) {

		ImmunityConfigGroup.SourceParams product = immunity.getOrAddSource(PRIOR_B);
		for (StrainSpec target : MultiSeasonStrains.all())
			for (DiseaseStatus status : ImmunityConfigGroup.SUPPORTED_TARGETS)
				product.setProtection(status, target.strain(), priorBCurve(target.strain(), status, parameters));

		VirusStrain[] strains = MultiSeasonStrains.all().stream().map(StrainSpec::strain).toArray(VirusStrain[]::new);
		VaccinationConfigGroup.VaccinationParams neutral = ConfigUtils.addOrGetModule(config, VaccinationConfigGroup.class)
				.getOrAddParams(PRIOR_B);
		neutral.setInfectivity(VaccinationConfigGroup.forStrain(strains).atDay(0, 1.0));
		neutral.setBoostInfectivity(VaccinationConfigGroup.forStrain(strains).atDay(0, 1.0));

		PriorImmunityConfigGroup who = ConfigUtils.addOrGetModule(config, PriorImmunityConfigGroup.class);
		who.setProduct(PRIOR_B);
		who.setShareByAge(Map.of(0, 0.0, (int) Math.round(parameters.priorBRampAge()), parameters.priorBMaxShare()));
	}

	/**
	 * The curve of the product of the earlier B/Victoria exposure: against B/Victoria, infection and symptoms, what an infection
	 * {@code priorBYears} before the start has left (peak times the decay over those years), decaying on with the half-life of B;
	 * against everything else, nothing.
	 */
	static ProtectionCurve priorBCurve(VirusStrain target, DiseaseStatus status, Parameters parameters) {

		if (!target.equals(MultiSeasonStrains.BVIC) || !PROTECTED.contains(status))
			return ProtectionCurve.NONE;

		double halfLifeDays = parameters.halfLifeBvicYears() * 365.0;
		double atStart = parameters.peak() * Math.pow(0.5, parameters.priorBYears() * 365.0 / halfLifeDays);
		double[] points = new double[CURVE_DAYS.length * 2];
		for (int i = 0; i < CURVE_DAYS.length; i++) {
			points[2 * i] = CURVE_DAYS[i];
			points[2 * i + 1] = Math.round(atStart * Math.pow(0.5, CURVE_DAYS[i] / halfLifeDays) * 10_000) / 10_000.0;
		}
		return ProtectionCurve.of(points);
	}

	/** The curve of the protection against {@code status} of a strain {@code target} that an infection with {@code source} gives. */
	public static ProtectionCurve curve(VirusStrain source, VirusStrain target, DiseaseStatus status, Parameters parameters) {

		double relation = relation(source, target, status, parameters);
		if (relation == 0.0)
			return ProtectionCurve.NONE;

		double halfLifeDays = halfLifeYears(lineage(source), parameters) * 365.0;
		double[] points = new double[CURVE_DAYS.length * 2];
		for (int i = 0; i < CURVE_DAYS.length; i++) {
			double protection = relation * parameters.peak() * Math.pow(0.5, CURVE_DAYS[i] / halfLifeDays);
			points[2 * i] = CURVE_DAYS[i];
			points[2 * i + 1] = Math.round(protection * 10_000) / 10_000.0;
		}
		return ProtectionCurve.of(points);
	}

	/**
	 * The relation {@code r} of two strains for a target status: 1 for a strain with itself and for the two A(H1N1)pdm09
	 * strains (A3), the drift factor between the two A(H3N2) strains (A5), the heterosubtypic value between A(H3N2) and
	 * A(H1N1)pdm09 for showing symptoms only (D2, A4), 0 against infection between subtypes, 0 between influenza A and B
	 * (A13), and 0 against every status beyond showing symptoms (A14).
	 */
	static double relation(VirusStrain source, VirusStrain target, DiseaseStatus status, Parameters parameters) {

		if (!PROTECTED.contains(status))
			return 0.0;
		if (source.equals(target))
			return 1.0;

		Lineage from = lineage(source);
		Lineage to = lineage(target);

		if (from == Lineage.H1N1PDM09 && to == Lineage.H1N1PDM09)
			return parameters.h1n1Pair();
		if (from == Lineage.H3N2 && to == Lineage.H3N2)
			return parameters.driftK();
		if (from == Lineage.BVIC || to == Lineage.BVIC)
			return 0.0;

		// A(H3N2) and A(H1N1)pdm09
		return status == DiseaseStatus.showingSymptoms ? parameters.heterosubtypicSymptoms() : 0.0;
	}

	private static double halfLifeYears(Lineage lineage, Parameters parameters) {
		return switch (lineage) {
			case H3N2 -> parameters.halfLifeH3n2Years();
			case H1N1PDM09 -> parameters.halfLifeH1n1Years();
			case BVIC -> parameters.halfLifeBvicYears();
		};
	}

	private static Lineage lineage(VirusStrain strain) {
		if (strain.equals(MultiSeasonStrains.H3N2_2022_23) || strain.equals(MultiSeasonStrains.H3N2K_2025_26))
			return Lineage.H3N2;
		if (strain.equals(MultiSeasonStrains.H1N1PDM09_2023_24) || strain.equals(MultiSeasonStrains.H1N1PDM09_2024_25))
			return Lineage.H1N1PDM09;
		if (strain.equals(MultiSeasonStrains.BVIC))
			return Lineage.BVIC;
		throw new IllegalArgumentException("Strain " + strain + " is not a strain of the multi-season scenario");
	}
}
