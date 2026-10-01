package org.matsim.episim.run.modules;

import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.episim.EpisimUtils;
import org.matsim.episim.model.VaccinationType;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The share of the population that carries immunity at the start of a run, by age (docs/influenza-multiseasonal.md, decision D17).
 *
 * <p>The immunity is given as a product of the {@code immunity} group (see {@link PriorImmunityModel}): an agent of the share
 * gets an entry of that product in its vaccination history on the first day, and the curve of the product says what it
 * protects against. The product stands for earlier infections and vaccinations; it is not a vaccine of the run, and the vaccination
 * columns of the output count it.</p>
 *
 * <p>Without a product ({@code product} empty, the default) nobody gets anything, which is the state of the single-season
 * scenarios.</p>
 */
public final class PriorImmunityConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUPNAME = "priorImmunity";

	private static final String PRODUCT = "product";
	private static final String SHARE_BY_AGE = "shareByAge";

	private String product = "";

	/** Share of agents of an age who get the product, between the keys linear, the first and the last value held. */
	private final NavigableMap<Integer, Double> shareByAge = new TreeMap<>();

	public PriorImmunityConfigGroup() {
		super(GROUPNAME);
	}

	@StringGetter(PRODUCT)
	String getProductName() {
		return product;
	}

	@StringSetter(PRODUCT)
	void setProductName(String product) {
		this.product = product == null ? "" : product.strip();
	}

	/** The product the agents get, {@code null} if the group is not used. */
	public VaccinationType getProduct() {
		return product.isEmpty() ? null : VaccinationType.of(product);
	}

	public void setProduct(VaccinationType product) {
		this.product = product == null ? "" : product.toString();
	}

	@StringGetter(SHARE_BY_AGE)
	String getShareByAgeString() {
		StringBuilder text = new StringBuilder();
		shareByAge.forEach((age, share) -> text.append(text.length() > 0 ? ";" : "").append(age).append("=").append(share));
		return text.toString();
	}

	@StringSetter(SHARE_BY_AGE)
	void setShareByAgeString(String text) {
		Map<Integer, Double> parsed = new TreeMap<>();
		if (text != null && !text.isBlank()) {
			for (String entry : text.split(";")) {
				String[] parts = entry.split("=");
				if (parts.length != 2)
					throw new IllegalArgumentException("'" + entry + "' in " + SHARE_BY_AGE + " is not of the form age=share");
				parsed.put(Integer.parseInt(parts[0].strip()), Double.parseDouble(parts[1].strip()));
			}
		}
		setShareByAge(parsed);
	}

	public Map<Integer, Double> getShareByAge() {
		return java.util.Collections.unmodifiableNavigableMap(shareByAge);
	}

	/**
	 * @throws IllegalArgumentException if a share is not between 0 and 1
	 */
	public void setShareByAge(Map<Integer, Double> shares) {
		for (Map.Entry<Integer, Double> e : shares.entrySet())
			if (!(e.getValue() >= 0.0 && e.getValue() <= 1.0))
				throw new IllegalArgumentException("The share " + e.getValue() + " of age " + e.getKey() + " is not between 0 and 1");
		shareByAge.clear();
		shareByAge.putAll(shares);
	}

	/** The share of agents of this age who get the product. */
	public double shareAt(int age) {
		return shareByAge.isEmpty() ? 0.0 : EpisimUtils.interpolateEntry(shareByAge, age);
	}

	@Override
	protected void checkConsistency(org.matsim.core.config.Config config) {
		super.checkConsistency(config);
		if (!product.isEmpty() && shareByAge.isEmpty())
			throw new IllegalStateException("Config group '" + GROUPNAME + "' names the product '" + product + "' but has no '"
				+ SHARE_BY_AGE + "', so nobody would get it");
	}
}
