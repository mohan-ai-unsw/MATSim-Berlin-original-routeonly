package org.matsim.run.scoring;

import com.google.inject.Inject;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.modechoice.EstimatorContext;
import org.matsim.modechoice.estimators.TripScoreEstimator;

/**
 * Provides the pseudo random score to the estimator.
 */
public class PseudoRandomTripScoreEstimator implements TripScoreEstimator {

	private final PseudoRandomScorer scorer;

	@Inject
	public PseudoRandomTripScoreEstimator(PseudoRandomScorer scorer) {
		this.scorer = scorer;
	}

	@Override
	public double estimate(EstimatorContext context, String mainMode, TripStructureUtils.Trip trip) {
		return scorer.scoreTrip(context.person.getId(), mainMode, trip);
	}
}
