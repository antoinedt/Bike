package com.bike.trainer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** Single app-wide preferences DataStore, shared by settings and Strava tokens. */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "bike_prefs")
