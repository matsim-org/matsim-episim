package org.matsim.episim.run.scenarios;

import org.matsim.episim.model.FaceMask;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.episim.policy.Restriction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The policy of the multi-season influenza scenario (docs/influenza-multiseasonal.md, sections 1.3 and 6.2; decisions D6, D8;
 * assumption A28): the masks of 2022/23 and the school closures in the school holidays of the whole horizon.
 */
public final class MultiSeasonPolicy {

	/** The containers in which masks are worn in 2022/23, as in the COVID Cologne scenario. */
	public static final String[] MASK_CONTAINERS = {"pt", "errands", "shop_daily", "shop_other"};

	/** The containers of the working contacts, whose attendance a variant reduces in the Christmas holidays. */
	public static final String[] WORK_CONTAINERS = {"work", "business"};

	/** Share of cloth masks in {@link #MASK_CONTAINERS}, as in the COVID Cologne scenario. */
	public static final double CLOTH_MASKS = 0.45;

	/** Share of surgical masks in {@link #MASK_CONTAINERS}, as in the COVID Cologne scenario. */
	public static final double SURGICAL_MASKS = 0.45;

	/**
	 * Last day with masks. The NRW requirement in local public transport ended on 1 February 2023 (long-distance transport:
	 * 2 February 2023), so the masks are removed from that day.
	 */
	public static final LocalDate LAST_MASK_DAY = LocalDate.parse("2023-01-31");

	/** A school holiday of Nordrhein-Westfalen, first and last day both included. */
	public record Holiday(String schoolYear, String name, LocalDate first, LocalDate last) {
	}

	private MultiSeasonPolicy() {
	}

	/**
	 * The policy for the horizon: masks from its first day to {@link #LAST_MASK_DAY}, and the closure of the schools in every
	 * holiday of the file that touches the horizon.
	 */
	public static FixedPolicy.ConfigBuilder build(Path holidaysFile, LocalDate horizonStart, LocalDate horizonEnd) throws IOException {
		return build(holidaysFile, horizonStart, horizonEnd, MultiSeasonVariant.BASE);
	}

	/**
	 * As {@link #build(Path, LocalDate, LocalDate)} with the levers of a variant: the attendance of the schools in the holidays,
	 * and a reduction of the contacts at work in the Christmas holidays.
	 */
	public static FixedPolicy.ConfigBuilder build(Path holidaysFile, LocalDate horizonStart, LocalDate horizonEnd,
												  MultiSeasonVariant variant) throws IOException {

		FixedPolicy.ConfigBuilder policy = FixedPolicy.config();

		masks(policy, horizonStart);
		for (Holiday holiday : readHolidays(holidaysFile)) {
			if (holiday.last().isBefore(horizonStart) || holiday.first().isAfter(horizonEnd))
				continue;
			InfluenzaParameterisation.closeSchools(policy, holiday.first().toString(), holiday.last().toString(),
					variant.holidaySchoolFraction());
			if (holiday.name().equals("christmas") && variant.christmasWorkFraction() < 1.0) {
				policy.restrict(holiday.first(), variant.christmasWorkFraction(), WORK_CONTAINERS);
				policy.restrict(holiday.last().plusDays(1), 1.0, WORK_CONTAINERS);
			}
		}

		return policy;
	}

	/**
	 * 45 % cloth and 45 % surgical masks in the containers of {@link #MASK_CONTAINERS} from {@code horizonStart}, and none
	 * from the day after {@link #LAST_MASK_DAY}. A restriction with no mask share at all would leave the masks as they are,
	 * so the end is a restriction with both shares set to zero.
	 */
	static void masks(FixedPolicy.ConfigBuilder policy, LocalDate horizonStart) {
		policy.restrict(horizonStart, Restriction.ofMask(Map.of(FaceMask.CLOTH, CLOTH_MASKS, FaceMask.SURGICAL, SURGICAL_MASKS)),
				MASK_CONTAINERS);
		policy.restrict(LAST_MASK_DAY.plusDays(1), Restriction.ofMask(Map.of(FaceMask.CLOTH, 0.0, FaceMask.SURGICAL, 0.0)),
				MASK_CONTAINERS);
	}

	/**
	 * Reads the holiday file: comment lines start with {@code #}; the header names the columns {@code school_year},
	 * {@code holiday}, {@code first_day} and {@code last_day}; the separator is a tab.
	 */
	public static List<Holiday> readHolidays(Path file) throws IOException {
		List<Holiday> holidays = new ArrayList<>();
		String[] header = null;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#"))
				continue;
			String[] cols = line.split("\t", -1);
			if (header == null) {
				header = cols;
				continue;
			}
			holidays.add(new Holiday(cols[column(header, "school_year")], cols[column(header, "holiday")],
					LocalDate.parse(cols[column(header, "first_day")]), LocalDate.parse(cols[column(header, "last_day")])));
		}
		if (header == null)
			throw new IllegalArgumentException("No header in " + file);

		// the closure ends the day after the holiday; two holidays that touch would put two values on one day
		for (int i = 1; i < holidays.size(); i++)
			if (!holidays.get(i).first().isAfter(holidays.get(i - 1).last().plusDays(1)))
				throw new IllegalArgumentException("The holidays " + holidays.get(i - 1) + " and " + holidays.get(i) + " touch or overlap");

		return holidays;
	}

	private static int column(String[] header, String name) {
		for (int i = 0; i < header.length; i++)
			if (header[i].equals(name))
				return i;
		throw new IllegalArgumentException("Column '" + name + "' missing in the holiday file");
	}
}
