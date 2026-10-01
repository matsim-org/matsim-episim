package org.matsim.episim.run.scenarios;

/**
 * The levers of the multi-season scenario that sensitivity runs change (docs/influenza-multiseasonal.md, sections 6.3 and 12);
 * {@link #BASE} is the scenario of the decision log.
 *
 * @param holidaySchoolFraction   remaining attendance of the schools in the school holidays; 0.2 is the assumption of the COVID
 *                                Cologne scenario (A38)
 * @param weatherOutdoorFraction  whether the outdoor fraction of the leisure contacts comes from the weather (D7, A36) or from the
 *                                fixed pattern of the single-season scenario, repeated every year
 * @param christmasWorkFraction   remaining share of the contacts at {@code work} and {@code business} in the Christmas holidays;
 *                                1.0 means no reduction (A38)
 * @param importFactor            factor on the import constant of the multi-season scenario (D10, the same for all seasons); 1.0
 *                                is the base scenario
 * @param priorBMaxShare          share of the adults who carry B/Victoria immunity on the first day (D17, A39); 0 switches the
 *                                prior immunity off
 * @param bvicInfectiousnessFactor factor on the infectiousness of B/Victoria relative to the other strains (Q26 option a); 1.0
 *                                keeps all strains equal (D13)
 * @param immunity                the parameters of the immunity curves (section 5); {@code priorBMaxShare} of them is replaced by
 *                                the field above
 */
public record MultiSeasonVariant(double holidaySchoolFraction, boolean weatherOutdoorFraction, double christmasWorkFraction,
								 double importFactor, double priorBMaxShare, double bvicInfectiousnessFactor,
								 MultiSeasonImmunity.Parameters immunity) {

	public static final MultiSeasonVariant BASE = new MultiSeasonVariant(InfluenzaParameterisation.HOLIDAY_SCHOOL_FRACTION, true, 1.0, 1.0,
			MultiSeasonImmunity.Parameters.defaults().priorBMaxShare(), 1.0,
			MultiSeasonImmunity.Parameters.defaults());

	public MultiSeasonVariant {
		if (holidaySchoolFraction < 0 || holidaySchoolFraction > 1 || christmasWorkFraction < 0 || christmasWorkFraction > 1)
			throw new IllegalArgumentException("Fractions have to be between 0 and 1: " + holidaySchoolFraction + ", " + christmasWorkFraction);
		if (!(importFactor > 0))
			throw new IllegalArgumentException("The import factor has to be positive: " + importFactor);
		if (priorBMaxShare < 0 || priorBMaxShare > 1)
			throw new IllegalArgumentException("The share has to be between 0 and 1: " + priorBMaxShare);
		if (!(bvicInfectiousnessFactor > 0))
			throw new IllegalArgumentException("The infectiousness factor has to be positive: " + bvicInfectiousnessFactor);
		if (immunity == null)
			throw new IllegalArgumentException("The immunity parameters are missing");
	}

	public MultiSeasonVariant withHolidaySchoolFraction(double fraction) {
		return new MultiSeasonVariant(fraction, weatherOutdoorFraction, christmasWorkFraction, importFactor, priorBMaxShare, bvicInfectiousnessFactor, immunity);
	}

	public MultiSeasonVariant withWeatherOutdoorFraction(boolean weather) {
		return new MultiSeasonVariant(holidaySchoolFraction, weather, christmasWorkFraction, importFactor, priorBMaxShare, bvicInfectiousnessFactor, immunity);
	}

	public MultiSeasonVariant withChristmasWorkFraction(double fraction) {
		return new MultiSeasonVariant(holidaySchoolFraction, weatherOutdoorFraction, fraction, importFactor, priorBMaxShare, bvicInfectiousnessFactor, immunity);
	}

	public MultiSeasonVariant withImportFactor(double factor) {
		return new MultiSeasonVariant(holidaySchoolFraction, weatherOutdoorFraction, christmasWorkFraction, factor, priorBMaxShare, bvicInfectiousnessFactor, immunity);
	}

	public MultiSeasonVariant withPriorBMaxShare(double share) {
		return new MultiSeasonVariant(holidaySchoolFraction, weatherOutdoorFraction, christmasWorkFraction, importFactor, share, bvicInfectiousnessFactor, immunity);
	}

	public MultiSeasonVariant withBvicInfectiousnessFactor(double factor) {
		return new MultiSeasonVariant(holidaySchoolFraction, weatherOutdoorFraction, christmasWorkFraction, importFactor, priorBMaxShare, factor, immunity);
	}

	public MultiSeasonVariant withImmunity(MultiSeasonImmunity.Parameters parameters) {
		return new MultiSeasonVariant(holidaySchoolFraction, weatherOutdoorFraction, christmasWorkFraction, importFactor, priorBMaxShare,
				bvicInfectiousnessFactor, parameters);
	}
}
