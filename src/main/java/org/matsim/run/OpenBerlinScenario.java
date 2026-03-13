package org.matsim.run;

import com.google.inject.Key;
import com.google.inject.name.Names;
import org.matsim.analysis.QsimTimingModule;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.SampleOptions;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleLinkSpeedCalculator;
import org.matsim.contrib.bicycle.BicycleLinkSpeedCalculatorDefaultImpl;
import org.matsim.contrib.bicycle.BicycleTravelTime;
import org.matsim.contrib.emissions.HbefaRoadTypeMapping;
import org.matsim.contrib.emissions.OsmHbefaMapping;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.run.scoring.AdvancedScoringConfigGroup;
import org.matsim.run.scoring.AdvancedScoringModule;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;
import org.matsim.contrib.decongestion.DecongestionConfigGroup;
import org.matsim.contrib.decongestion.DecongestionModule;
import org.matsim.contrib.roadpricing.RoadPricing;
import org.matsim.contrib.roadpricing.RoadPricingConfigGroup;
import org.matsim.contrib.roadpricing.RoadPricingScheme;
import org.matsim.contrib.roadpricing.RoadPricingUtils;

import java.lang.reflect.Method;
import java.util.List;

@CommandLine.Command(header = ":: Open Berlin Scenario ::", version = OpenBerlinScenario.VERSION, mixinStandardHelpOptions = true, showDefaultValues = true)
public class OpenBerlinScenario extends MATSimApplication {

	public static final String VERSION = "6.4";
	public static final String CRS = "EPSG:25832";

	//	To decrypt hbefa input files set MATSIM_DECRYPTION_PASSWORD as environment variable. ask VSP for access.
	private static final String HBEFA_2020_PATH = "https://svn.vsp.tu-berlin.de/repos/public-svn/3507bb3997e5657ab9da76dbedbb13c9b5991d3e/0e73947443d68f95202b71a156b337f7f71604ae/";
	private static final String HBEFA_FILE_COLD_DETAILED = HBEFA_2020_PATH + "82t7b02rc0rji2kmsahfwp933u2rfjlkhfpi2u9r20.enc";
	private static final String HBEFA_FILE_WARM_DETAILED = HBEFA_2020_PATH + "944637571c833ddcf1d0dfcccb59838509f397e6.enc";
	private static final String HBEFA_FILE_COLD_AVERAGE = HBEFA_2020_PATH + "r9230ru2n209r30u2fn0c9rn20n2rujkhkjhoewt84202.enc" ;
	private static final String HBEFA_FILE_WARM_AVERAGE = HBEFA_2020_PATH + "7eff8f308633df1b8ac4d06d05180dd0c5fdf577.enc";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(10, 25, 3, 1);

	@CommandLine.Option(names = "--plan-selector",
		description = "Plan selector to use.",
		defaultValue = DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
	private String planSelector;

	public OpenBerlinScenario() {
		super(String.format("input/v%s/berlin-v%s.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(OpenBerlinScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {

		SimWrapperConfigGroup sw = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		if (sample.isSet()) {
			double sampleSize = sample.getSample();

			config.qsim().setFlowCapFactor(sampleSize);
			config.qsim().setStorageCapFactor(sampleSize);

			// Counts can be scaled with sample size
			config.counts().setCountsScaleFactor(sampleSize);
			sw.sampleSize = sampleSize;

			config.controller().setRunId(sample.adjustName(config.controller().getRunId()));
			config.controller().setOutputDirectory(sample.adjustName(config.controller().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
		}

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);

		// overwrite ride scoring params with values derived from car
		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(config.scoring(), 1.0);
		Activities.addScoringParams(config, true);

		// Required for all calibration strategies
		for (String subpopulation : List.of("person", "freight", "goodsTraffic", "commercialPersonTraffic", "commercialPersonTraffic_service")) {
			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(planSelector)
					.setWeight(subpopulation.equals("person") ? 0.85 : 1.0)
					.setSubpopulation(subpopulation)
			);

			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
					.setWeight(subpopulation.equals("person") ? 0.05 : 0.15)
					.setSubpopulation(subpopulation)
			);
		}

		config.replanning().addStrategySettings(
			new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.TimeAllocationMutator)
				.setWeight(0.05)
				.setSubpopulation("person")
		);

		config.replanning().addStrategySettings(
			new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.SubtourModeChoice)
				.setWeight(0.05)
				.setSubpopulation("person")
		);

		// Need to switch to warning for best score
		if (planSelector.equals(DefaultPlanStrategiesModule.DefaultSelector.BestScore)) {
			config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
		}

		// Bicycle config must be present
		ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);

		// --- Dynamic decongestion pricing (Ihab-style) ---
		ConfigUtils.addOrGetModule(config, RoadPricingConfigGroup.class);
		DecongestionConfigGroup dc = ConfigUtils.addOrGetModule(config, DecongestionConfigGroup.class);
		configureDecongestion(dc);

		// Add emissions configuration
		EmissionsConfigGroup eConfig = ConfigUtils.addOrGetModule(config, EmissionsConfigGroup.class);
		eConfig.setDetailedColdEmissionFactorsFile(HBEFA_FILE_COLD_DETAILED);
		eConfig.setDetailedWarmEmissionFactorsFile(HBEFA_FILE_WARM_DETAILED);
		eConfig.setAverageColdEmissionFactorsFile(HBEFA_FILE_COLD_AVERAGE);
		eConfig.setAverageWarmEmissionFactorsFile(HBEFA_FILE_WARM_AVERAGE);
		eConfig.setHbefaTableConsistencyCheckingLevel(EmissionsConfigGroup.HbefaTableConsistencyCheckingLevel.consistent);
		eConfig.setDetailedVsAverageLookupBehavior(EmissionsConfigGroup.DetailedVsAverageLookupBehavior.tryDetailedThenTechnologyAverageThenAverageTable);
		eConfig.setEmissionsComputationMethod(EmissionsConfigGroup.EmissionsComputationMethod.StopAndGoFraction);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		// Bootstrap road pricing scheme so RoadPricing module can start.
		// Decongestion is expected to update tolls dynamically during iterations.
		var scheme = RoadPricingUtils.addOrGetMutableRoadPricingScheme(scenario);
		RoadPricingUtils.setType(scheme, RoadPricingScheme.TOLL_TYPE_LINK);
		RoadPricingUtils.setName(scheme, "bootstrap-zero-toll");
		RoadPricingUtils.setDescription(scheme, "Bootstrap scheme to satisfy RoadPricing startup; decongestion updates tolls dynamically.");
		RoadPricingUtils.createAndAddGeneralCost(
			scheme,
			org.matsim.core.utils.misc.Time.parseTime("00:00:00"),
			org.matsim.core.utils.misc.Time.parseTime("36:00:00"),
			0.0
		);

		// add hbefa link attributes.
		HbefaRoadTypeMapping roadTypeMapping = OsmHbefaMapping.build();
		roadTypeMapping.addHbefaMappings(scenario.getNetwork());
	}

	@Override
	protected void prepareControler(Controler controler) {

		controler.addOverridingModule(new SimWrapperModule());

		controler.addOverridingModule(new TravelTimeBinding());

		controler.addOverridingModule(new QsimTimingModule());

		controler.addOverridingModule(new DecongestionModule());

		// FIX:
		// RoadPricing is enabled below, so it MUST have a scheme (or a toll links file),
		// otherwise it crashes at startup. Provide a minimal "zero toll" bootstrap scheme.

		RoadPricing.configure(controler);

		// AdvancedScoring is specific to matsim-berlin!
		if (ConfigUtils.hasModule(controler.getConfig(), AdvancedScoringConfigGroup.class)) {
			controler.addOverridingModule(new AdvancedScoringModule());
			controler.getConfig().scoring().setExplainScores(true);
		} else {
			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).asEagerSingleton();
				}
			});
		}
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());
	}

	// IMPORTANT: these methods must be at CLASS LEVEL (not inside any other method)


	private static void configureDecongestion(DecongestionConfigGroup dc) {
		// This MATSim version uses fraction-of-iterations + update interval API.
		// Your config lastIteration is 500, so:
		// Paper setup:
		// k_update = 1        -> update every iteration
		// k^s = 1.0           -> smoothing/blending factor
		// d_min = 1 sec       -> tolerated average delay threshold
		// Kp = 0.003, Ki = 0, Kd = 0
		// use MSA             -> smoothen toll levels over iterations

		dc.setEnableDecongestionPricing(true);

		// In this MATSim version, "decongestionApproach" selects the controller type:
		// BangBang, PID, P_MC. For the paper setup we keep PID but set Ki=Kd=0.
		dc.setDecongestionApproach(DecongestionConfigGroup.DecongestionApproach.PID);

		dc.setInitialToll(0.0);

		// k_update = 1
		dc.setUpdatePriceInterval(1);

		// k^s = 1.0
		dc.setTollBlendFactor(1.0);

		// d_min = 1 sec
		dc.setToleratedAverageDelaySec(1.0);

		// Controller gains
		dc.setKp(0.003);
		dc.setKi(0.0);
		dc.setKd(0.0);

		// Use successive averages (MSA) for toll smoothing
		dc.setMsa(true);

		// When to start/stop adjusting prices (keep your current schedule)
		dc.setFractionOfIterationsToStartPriceAdjustment(0.00);
		dc.setFractionOfIterationsToEndPriceAdjustment(1.00);

		dc.setWriteOutputIteration(100);

		// NOTE on paper's "T = 900 sec" (15-min time bin):
		// This DecongestionConfigGroup does not expose a parameter for T in your version,
		// so it cannot be set here via config; it may be hard-coded or configured elsewhere.
	}

	/**
	 * Tries to invoke the first existing setter among methodNames.
	 * It will try multiple numeric parameter types to survive API changes:
	 * int/Integer, long/Long, double/Double.
	 */
	private static boolean invokeFirstExistingNumber(Object target, String[] methodNames, int value) {
		Class<?>[] paramTypes = new Class<?>[]{
			int.class, Integer.class,
			long.class, Long.class,
			double.class, Double.class
		};

		Object[] values = new Object[]{
			value, Integer.valueOf(value),
			(long) value, Long.valueOf(value),
			(double) value, Double.valueOf(value)
		};

		for (String name : methodNames) {
			for (int i = 0; i < paramTypes.length; i++) {
				try {
					Method m = target.getClass().getMethod(name, paramTypes[i]);
					m.invoke(target, values[i]);
					return true;
				} catch (NoSuchMethodException ignored) {
					// try next overload / next name
				} catch (Exception e) {
					throw new RuntimeException("Failed invoking " + name + "(" + paramTypes[i].getSimpleName() + ")", e);
				}
			}
		}

		System.err.println("Decongestion config: none of these methods exist on " + target.getClass().getName() + ": " +
			String.join(", ", methodNames));
		return false;
	}

	private static boolean invokeFirstExistingBoolean(Object target, String[] methodNames, boolean value) {
		for (String name : methodNames) {
			try {
				Method m = target.getClass().getMethod(name, boolean.class);
				m.invoke(target, value);
				return true;
			} catch (NoSuchMethodException ignored) {
				// try next
			} catch (Exception e) {
				throw new RuntimeException("Failed invoking " + name + "(boolean)", e);
			}
		}
		System.err.println("Decongestion config: none of these boolean methods exist on " + target.getClass().getName() + ": " +
			String.join(", ", methodNames));
		return false;
	}

	private static boolean invokeEnumIfPresent(Object target, String[] methodNames, String enumConstant) {
		for (String name : methodNames) {
			for (Method m : target.getClass().getMethods()) {
				if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;

				Class<?> p = m.getParameterTypes()[0];
				if (!p.isEnum()) continue;

				try {
					@SuppressWarnings({"rawtypes", "unchecked"})
					Object value = Enum.valueOf((Class<? extends Enum>) p, enumConstant);
					m.invoke(target, value);
					return true;
				} catch (IllegalArgumentException ignored) {
					// enum doesn't have that constant
				} catch (Exception e) {
					throw new RuntimeException("Failed invoking " + name + "(" + p.getSimpleName() + "=" + enumConstant + ")", e);
				}
			}
		}
		return false;
	}

	private static void printAllSingleArgSetters(Object target) {
		System.err.println("Decongestion config: available setters on " + target.getClass().getName() + ":");
		for (Method m : target.getClass().getMethods()) {
			if (m.getName().startsWith("set") && m.getParameterCount() == 1) {
				System.err.println("  " + m.getName() + "(" + m.getParameterTypes()[0].getSimpleName() + ")");
			}
		}
	}
// ... existing code ...
	/**
	 * Add travel time bindings for ride and freight modes, which are not actually network modes.
	 */
	public static final class TravelTimeBinding extends AbstractModule {

		private final boolean carOnly;

		public TravelTimeBinding() {
			this.carOnly = false;
		}

		public TravelTimeBinding(boolean carOnly) {
			this.carOnly = carOnly;
		}

		@Override
		public void install() {
			addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
			addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

			if (!carOnly) {
				addTravelTimeBinding("freight").to(Key.get(TravelTime.class, Names.named(TransportMode.truck)));
				addTravelDisutilityFactoryBinding("freight").to(Key.get(TravelDisutilityFactory.class, Names.named(TransportMode.truck)));


				bind(BicycleLinkSpeedCalculator.class).to(BicycleLinkSpeedCalculatorDefaultImpl.class);

				// Bike should use free speed travel time
				addTravelTimeBinding(TransportMode.bike).to(BicycleTravelTime.class);
				addTravelDisutilityFactoryBinding(TransportMode.bike).to(OnlyTimeDependentTravelDisutilityFactory.class);
			}
		}
	}

}
