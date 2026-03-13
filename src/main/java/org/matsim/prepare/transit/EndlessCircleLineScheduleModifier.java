/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2024 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.prepare.transit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.vehicles.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

// other schedule modifier in the make file implement Consumer<TransitSchedule>. But here we modify schedule and vehicles, so we cannot use that.
@CommandLine.Command(name = "endless-circle-line", description = "Modifies Berlin S41 and S42 to run circle multiple times.")
public class EndlessCircleLineScheduleModifier implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(EndlessCircleLineScheduleModifier.class);

	@CommandLine.Option(names = {"--transit-schedule"}, description = "Path to the transit schedule file", required = true)
	private String transitSchedulePath;
	@CommandLine.Option(names = {"--transit-vehicles"}, description = "Path to the transit vehicles file", required = true)
	private String transitVehiclesPath;
	@CommandLine.Option(names = {"--network"}, description = "Path to the network file (for validation only)", required = false)
	private String networkPath;
	@CommandLine.Option(names = "--output-transit-schedule", description = "Path to the output transit schedule file", required = true)
	private Path outputTransitSchedule;
	@CommandLine.Option(names = "--output-transit-vehicles", description = "Path to the output transit vehicles file", required = true)
	private Path outputTransitVehicles;

	private TransitSchedule schedule;
	private Vehicles transitVehicles;
	private TransitScheduleFactory transitScheduleFactory;

	public static void main(String[] args) {
		new EndlessCircleLineScheduleModifier().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		replaceS41S42With2LoopingRoutesEach();
		return 0;
	}

	@SuppressWarnings("rawtypes")
	private void replaceS41S42With2LoopingRoutesEach() {
		/*
		 * basic service pattern of the circle lines S41 and S42 is a 10 min headway service from 4:00 to 24:00 plus additional trains every 10 min
		 * from circa. 5:30 to 20:20 reinforcing to a 5 min headway.
		 *
		 * Here as an example values from a 2023 timetable:
		 * first S41---4715_0 departure at 4:08:24 in Beusselstr., then 10 min headway until 5:08:24, then 5 min until 20:18:24,
		 * then 10 min headway until 23:58:24.
		 *
		 * S42 was similar to S41 in December 2024, but was more complex in 2023:
		 * S42---5444_0 operated from 3:58:18 to,5:28:18 every 10 min, then every 5 min until 20:18:18, then every 10 min until 21:08:18.
		 * Then 5444_1 every 10 min from 21:18.18 until 22:08:18 (65 min loop time!) and 5444_5 from 22:23:18 every 10 min until 24:33:18.
		 *
		 * For simplification implement 2 looping TransitRoutes per circle line, both every 10 min with approximated first and last departure times.
		 */
		double loopingTravelTime = 60 * 60.0;
		double firstDepartureTime = 3 * 60 * 60. + 50 * 60.;
		double lastDepartureTime = 24 * 60 * 60. + 30 * 60.;
		double baseHeadway = 10 * 60.;
		double peakHeadwayStart = 5 * 60 * 60. + 20 * 60.;
		double peakHeadwayEnd = 20 * 60 * 60. + 20 * 60.;

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		schedule = scenario.getTransitSchedule();
		transitScheduleFactory = schedule.getFactory();
		transitVehicles = scenario.getTransitVehicles();
		TransitScheduleReader scheduleReader = new TransitScheduleReader(scenario);
		scheduleReader.readFile(transitSchedulePath);
		MatsimVehicleReader vehicleReader = new MatsimVehicleReader(transitVehicles);
		vehicleReader.readFile(transitVehiclesPath);

		// attempt to find ids automatically (change with every new input gtfs file)
		Tuple<Id<TransitLine>, Id<TransitRoute>> lineRouteS41 = findSingleLoopTransitRouteToCopy("S41");
		Tuple<Id<TransitLine>, Id<TransitRoute>> lineRouteS42 = findSingleLoopTransitRouteToCopy("S42");

		double typicalDepartureSecondS41 = findTypicalDepartureSecond(lineRouteS41.getFirst(), lineRouteS41.getSecond());
		double typicalDepartureSecondS42 = findTypicalDepartureSecond(lineRouteS42.getFirst(), lineRouteS42.getSecond());

		VehicleType vehicleType = findTypicalVehicleType(lineRouteS41.getFirst(), lineRouteS41.getSecond());

		// S41
		createLoopingTransitRoute(lineRouteS41.getFirst(), lineRouteS41.getSecond(),
			Id.create(lineRouteS41.getFirst() + "_loop", TransitRoute.class),
			loopingTravelTime, getNumberOfLoopings(firstDepartureTime, lastDepartureTime, loopingTravelTime),
			baseHeadway, firstDepartureTime + typicalDepartureSecondS41, vehicleType);

		createLoopingTransitRoute(lineRouteS41.getFirst(), lineRouteS41.getSecond(),
			Id.create(lineRouteS41.getFirst() + "_loop_peak", TransitRoute.class),
			loopingTravelTime, getNumberOfLoopings(peakHeadwayStart, peakHeadwayEnd, loopingTravelTime),
			baseHeadway, peakHeadwayStart + typicalDepartureSecondS41 + baseHeadway / 2, vehicleType);

		// add early morning service on following day
		createLoopingTransitRoute(lineRouteS41.getFirst(), lineRouteS41.getSecond(),
			Id.create(lineRouteS41.getFirst() + "_loop+24h", TransitRoute.class),
			loopingTravelTime, getNumberOfLoopings(firstDepartureTime + 24 * 3600., 30 * 3600., loopingTravelTime),
			baseHeadway, firstDepartureTime + 24 * 3600. + typicalDepartureSecondS41, vehicleType);

		// delete old non-looping transit route
		removeOldTransitRouteAndItsVehicles(lineRouteS41.getFirst(), lineRouteS41.getSecond());

		// S42
		createLoopingTransitRoute(lineRouteS42.getFirst(), lineRouteS42.getSecond(),
			Id.create(lineRouteS42.getFirst() + "_loop", TransitRoute.class),
			loopingTravelTime, getNumberOfLoopings(firstDepartureTime, lastDepartureTime, loopingTravelTime),
			baseHeadway, firstDepartureTime + typicalDepartureSecondS42, vehicleType);

		createLoopingTransitRoute(lineRouteS42.getFirst(), lineRouteS42.getSecond(),
			Id.create(lineRouteS42.getFirst() + "_loop_peak", TransitRoute.class),
			loopingTravelTime, getNumberOfLoopings(peakHeadwayStart, peakHeadwayEnd, loopingTravelTime),
			baseHeadway, peakHeadwayStart + typicalDepartureSecondS42 + baseHeadway / 2, vehicleType);

		// add early morning service on following day
		createLoopingTransitRoute(lineRouteS42.getFirst(), lineRouteS42.getSecond(),
			Id.create(lineRouteS42.getFirst() + "_loop+24h", TransitRoute.class),
			loopingTravelTime, getNumberOfLoopings(firstDepartureTime + 24 * 3600., 30 * 3600., loopingTravelTime),
			baseHeadway, firstDepartureTime + 24 * 3600. + typicalDepartureSecondS42, vehicleType);

		removeOldTransitRouteAndItsVehicles(lineRouteS42.getFirst(), lineRouteS42.getSecond());

		if (networkPath != null && !networkPath.isEmpty()) {
			MatsimNetworkReader networkReader = new MatsimNetworkReader(scenario.getNetwork());
			networkReader.readFile(networkPath);
			TransitScheduleValidator.ValidationResult validationResult = TransitScheduleValidator.validateAll(schedule, scenario.getNetwork());
			if (validationResult.isValid()) {
				log.info("TransitSchedule is valid according to TransitScheduleValidator.");
			} else {
				log.error("TransitSchedule is invalid according to TransitScheduleValidator.");
				for (TransitScheduleValidator.ValidationResult.ValidationIssue issue : validationResult.getIssues()) {
					log.error(issue.getMessage());
				}
				throw new IllegalStateException("invalid output schedule");
			}
		}

		TransitScheduleWriter transitScheduleWriter = new TransitScheduleWriter(schedule);
		transitScheduleWriter.writeFile(outputTransitSchedule.toString());
		MatsimVehicleWriter vehicleWriter = new MatsimVehicleWriter(transitVehicles);
		vehicleWriter.writeFile(outputTransitVehicles.toString());
	}

	private void createLoopingTransitRoute(Id<TransitLine> transitLineId, Id<TransitRoute> singleLoopingToCopyTransitRouteId,
										   Id<TransitRoute> loopingTransitRouteId,
										   double loopingTravelTime, long numberLoopings,
										   double headway, double firstDepartureTime,
										   VehicleType vehicleType) {

		TransitLine lineToModify = schedule.getTransitLines().get(transitLineId);
		TransitRoute routeToCopy = lineToModify.getRoutes().get(singleLoopingToCopyTransitRouteId);

		List<Id<Link>> loopingNetworkRouteLinks = new ArrayList<>();
		List<TransitRouteStop> transitRouteStops = new ArrayList<>();
		// add first stop manually
		TransitRouteStop firstRouteStop = transitScheduleFactory.createTransitRouteStop(
			routeToCopy.getStops().getLast().getStopFacility(),
			routeToCopy.getStops().getFirst().getArrivalOffset(),
			routeToCopy.getStops().getFirst().getDepartureOffset());
		firstRouteStop.setAwaitDepartureTime(true);
		transitRouteStops.add(firstRouteStop);

		loopingNetworkRouteLinks.add(firstRouteStop.getStopFacility().getLinkId());

		for (int loopingsDone = 0; loopingsDone < numberLoopings; loopingsDone++) {
			loopingNetworkRouteLinks.addAll(routeToCopy.getRoute().getLinkIds());
			loopingNetworkRouteLinks.add(routeToCopy.getRoute().getEndLinkId());

			// skip first and last stop and add merged stop instead to avoid stopping twice at loopStartTransitStopId
			for (TransitRouteStop stop : routeToCopy.getStops().subList(1, routeToCopy.getStops().size() - 1)) {
				TransitRouteStop transitRouteStop = transitScheduleFactory.createTransitRouteStop(stop.getStopFacility(),
					stop.getArrivalOffset().seconds() + loopingsDone * loopingTravelTime,
					stop.getDepartureOffset().seconds() + loopingsDone * loopingTravelTime);
				transitRouteStop.setAwaitDepartureTime(true);
				transitRouteStops.add(transitRouteStop);
			}
			// add last stop of this looping which is first stop of next looping
			TransitRouteStop lastRouteStop = transitScheduleFactory.createTransitRouteStop(
				routeToCopy.getStops().getLast().getStopFacility(),
				routeToCopy.getStops().getLast().getArrivalOffset().seconds() + loopingsDone * loopingTravelTime,
				routeToCopy.getStops().getFirst().getDepartureOffset().seconds() + (loopingsDone + 1) * loopingTravelTime);
			lastRouteStop.setAwaitDepartureTime(true);
			transitRouteStops.add(lastRouteStop);
		}
		// at least for S41 and S42 last link in network route ends at same node as first link -> continuous
		NetworkRoute networkRoute = RouteUtils.createNetworkRoute(loopingNetworkRouteLinks);
		TransitRoute loopingRoute = transitScheduleFactory.createTransitRoute(loopingTransitRouteId, networkRoute, transitRouteStops, "multiple loopings in one route");
		loopingRoute.setTransportMode(routeToCopy.getTransportMode());

		int departureIdCounter = 0;
		for (double departureTime = firstDepartureTime; departureTime < firstDepartureTime + loopingTravelTime; departureTime = departureTime + headway) {
			Id<Departure> departureId = Id.create(loopingTransitRouteId + "_" + departureIdCounter, Departure.class);
			Departure departure = transitScheduleFactory.createDeparture(departureId, departureTime);
			// create new vehicles and delete unused old ones later.
			Id<Vehicle> vehicleId = Id.createVehicleId("pt_" + loopingRoute.getId().toString() + "_" + departureIdCounter);
			Vehicle vehicle = transitVehicles.getFactory().createVehicle(vehicleId, vehicleType);
			transitVehicles.addVehicle(vehicle);
			departure.setVehicleId(vehicleId);
			loopingRoute.addDeparture(departure);
			departureIdCounter++;
		}

		lineToModify.addRoute(loopingRoute);
	}

	private VehicleType findTypicalVehicleType(Id<TransitLine> lineId, Id<TransitRoute> routeId) {
		TransitRoute route = schedule.getTransitLines().get(lineId).getRoutes().get(routeId);
		Optional<Departure> exampleDepartureOptional = route.getDepartures().values().stream()
			// find a typical VehicleType, avoid early hours short train
			.filter(dep -> dep.getDepartureTime() > 8 * 60 * 60 && dep.getDepartureTime() < 20 * 60 * 60)
			.findFirst();
		if (exampleDepartureOptional.isEmpty()) {
			log.error("No suitable Departure found in line {}, route {} to use as an example for the VehicleType to be used on the new endless looping TransitRoute.",
				lineId, routeId);
			throw new IllegalStateException("No suitable Departure found to define VehicleType.");
		}
		return transitVehicles.getVehicles().get(exampleDepartureOptional.get().getVehicleId()).getType();
	}

	private Tuple<Id<TransitLine>, Id<TransitRoute>> findSingleLoopTransitRouteToCopy(String lineName) {
		List<Tuple<Id<TransitLine>, Id<TransitRoute>>> candidates = new ArrayList<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			if (line.getAttributes().getAttribute("gtfs_route_short_name").equals(lineName)) {
				for (TransitRoute route : line.getRoutes().values()) {
					if (route.getStops().getFirst().getStopFacility().getStopAreaId().equals(
						route.getStops().getLast().getStopFacility().getStopAreaId()) &&
						route.getStops().size() == 28 &&
						route.getStops().getLast().getArrivalOffset().seconds() > 58 * 60 &&
						route.getStops().getLast().getArrivalOffset().seconds() < 60 * 60 &&
						route.getDepartures().size() > 100) {
						// is looping, has all stops and has travel time ca. 60 min (not 65 min) and a significant number of departures
						// usually there is only one looping TransitRoute *_0 with > 200 departures or two looping TransitRoutes, of which one has > 150 departures and operates all day and the other has < 30 departures.
						candidates.add(new Tuple<>(line.getId(), route.getId()));
					}
				}
			}
		}

		switch (candidates.size()) {
			case 0:
				log.error("No suitable circle line and route found that loops with the correct number of stops and travel time for line {}. Check for construction work and timetable changes. A day with disturbed circle line is a bad choice.", lineName);
				throw new IllegalStateException("No suitable line and route found that loops with the correct number of stops and travel time for line " + lineName);
			case 1:
				return candidates.getFirst();
			default:
				log.error("Found multiple circle line candidates for {}. This is unusual. Please check manually which is the best fit. Listing candidates here ", lineName);
				for (Tuple<Id<TransitLine>, Id<TransitRoute>> tuple : candidates) {
					log.error("line {}, route {}", tuple.getFirst(), tuple.getSecond());
				}
				throw new IllegalStateException("Aborting.");
		}
	}

	/**
	 * Find typical departure time offset from hour. This is important to keep waiting times and headways in respect to other lines similar.
	 */
	private double findTypicalDepartureSecond(Id<TransitLine> transitLineId, Id<TransitRoute> transitRouteId) {
		TransitRoute transitRoute = schedule.getTransitLines().get(transitLineId).getRoutes().get(transitRouteId);
		Map<Double, Integer> departureSecond2Count = new HashMap<>();
		for (Departure departure : transitRoute.getDepartures().values()) {
			// usually operates every 10 or 5 minutes, so % 3600 reduces by hours and % 600 reduces by 10 min headway
			double offsetFromHour = (departure.getDepartureTime() % 3600) % 600;
			departureSecond2Count.put(offsetFromHour, departureSecond2Count.getOrDefault(offsetFromHour, 0) + 1);
		}
		Optional<Map.Entry<Double, Integer>> mostFrequentDepartureSecond = departureSecond2Count.entrySet().stream()
			.max(Comparator.comparingInt(Map.Entry::getValue));
		// most frequent offset should be the one found during both 5 min and 10 min headways
		if (mostFrequentDepartureSecond.isEmpty() || mostFrequentDepartureSecond.get().getValue() < 50) {
			log.error("Could not determine typical departure time for {}, {}", transitLineId, transitRouteId);
			throw new IllegalStateException("Could not determine typical departure time.");
		}
		return mostFrequentDepartureSecond.get().getKey();
	}

	private long getNumberOfLoopings(double firstDepartureTime, double lastDepartureTime, double loopTravelTime) {
		return Math.round((lastDepartureTime - firstDepartureTime - loopTravelTime) / loopTravelTime);
	}

	private void removeOldTransitRouteAndItsVehicles(Id<TransitLine> transitLineId, Id<TransitRoute> transitRouteId) {
		TransitRoute oldNonLoopingTransitRouteToDelete = schedule.getTransitLines().get(transitLineId).getRoutes().get(transitRouteId);
		oldNonLoopingTransitRouteToDelete.getDepartures().values().forEach(dep -> transitVehicles.removeVehicle(dep.getVehicleId()));
		schedule.getTransitLines().get(transitLineId).removeRoute(oldNonLoopingTransitRouteToDelete);
	}
}
