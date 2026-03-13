#!/usr/bin/env python
# -*- coding: utf-8 -*-

import numpy as np
import os
from matsim.scenariogen.data import EconomicStatus, TripMode, preparation, run_create_ref_data


from extract_ref_data import trip_filter

def person_filter(df):
    df = df[df.reporting_day <= 4]
    df = df[df.location == "Berlin"]

    return df

if __name__ == "__main__":
    d = os.path.expanduser("~/Development/matsim-scenarios/shared-svn/projects/matsim-berlin/data/SrV/")

    result = run_create_ref_data.create(
        d + "Berlin+Umland",
        person_filter, trip_filter,
        run_create_ref_data.InvalidHandling.REMOVE_TRIPS,
    )

    t = result.trips

    t["speed"] = (t.gis_length * 3600) / (t.duration * 60)
    t = t[t.main_mode == TripMode.BIKE]

    t.to_csv('bike_speeds.csv', columns=["t_weight", "age", "speed"], index=False)