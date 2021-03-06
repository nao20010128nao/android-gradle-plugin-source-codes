/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.api.InstantAppVariantOutput;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.internal.variant.InstantAppVariantOutputData;
import org.gradle.api.tasks.bundling.Zip;

/**
 * Implementation of variant output for iapk-generating variants.
 */
public class InstantAppVariantOutputImpl extends BaseVariantOutputImpl implements
        InstantAppVariantOutput {
    private final InstantAppVariantOutputData variantOutputData;

    public InstantAppVariantOutputImpl(@NonNull InstantAppVariantOutputData variantOutputData) {
        this.variantOutputData = variantOutputData;
    }

    @Override
    public int getVersionCode() {
        return variantOutputData.getVersionCode();
    }

    @Nullable
    @Override
    public Zip getPackageInstantApp() {
        return variantOutputData.packageInstantAppTask;
    }

    @NonNull
    @Override
    protected BaseVariantOutputData getVariantOutputData() {
        return variantOutputData;
    }
}
