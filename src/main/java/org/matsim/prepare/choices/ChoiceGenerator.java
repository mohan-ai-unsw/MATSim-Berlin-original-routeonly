package org.matsim.prepare.choices;

import org.matsim.api.core.v01.population.Plan;
import org.matsim.modechoice.PlanCandidate;
import org.matsim.modechoice.PlanModel;

import java.util.List;
import java.util.Set;

/**
 * Generate choices for a plan.
 */
public interface ChoiceGenerator {

	/**
	 * Generate choices for a plan.
	 */
	List<PlanCandidate> generate(Plan plan, PlanModel planModel, Set<String> consideredModes);


}
