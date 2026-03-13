package org.matsim.run.scoring;

import com.google.inject.Inject;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.*;

/**
 * Same as {@link org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory} but with {@link PiecewiseLinearlLegScoring}.
 */
public class AdvancedScoringFunctionFactory implements ScoringFunctionFactory {

	private final Config config;
	private final AdvancedScoringConfigGroup scoring;
	private final AnalysisMainModeIdentifier mmi;
	private final ScoringParametersForPerson params;
	private final PseudoRandomScorer pseudoRNG;

	@Inject
	public AdvancedScoringFunctionFactory(Config config, AnalysisMainModeIdentifier mmi,
										  ScoringParametersForPerson params, PseudoRandomScorer pseudoRNG) {
		this.config = config;
		this.scoring = ConfigUtils.addOrGetModule(config, AdvancedScoringConfigGroup.class);
		this.mmi = mmi;
		this.params = params;
		this.pseudoRNG = pseudoRNG;
	}

	@Override
	public ScoringFunction createNewScoringFunction(Person person) {
		final ScoringParameters parameters = params.getScoringParameters(person);

		SumScoringFunction sumScoringFunction = new SumScoringFunction();
		sumScoringFunction.addScoringFunction(new CharyparNagelActivityScoring(parameters));

		if (scoring.pseudoRamdomScale > 0) {
			sumScoringFunction.addScoringFunction(new PseudoRandomTripScoring(person.getId(), mmi, pseudoRNG));
		}

		// replaced original leg scoring
		sumScoringFunction.addScoringFunction(new PiecewiseLinearlLegScoring(parameters, config.transit().getTransitModes()));
		sumScoringFunction.addScoringFunction(new CharyparNagelMoneyScoring(parameters));
		sumScoringFunction.addScoringFunction(new CharyparNagelAgentStuckScoring(parameters));
		sumScoringFunction.addScoringFunction(new ScoreEventScoring());
		return sumScoringFunction;
	}

}
