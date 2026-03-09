/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

public class Sticker implements Parcelable {
    public final String imageFileName;
    public List<String> emojis;
    public final String accessibilityText;
    public long size;
    public String validationError; // transient, set by validator

    public Sticker(String imageFileName, List<String> emojis, String accessibilityText) {
        this.imageFileName = imageFileName;
        this.emojis = emojis;
        this.accessibilityText = accessibilityText;
    }

    protected Sticker(Parcel in) {
        imageFileName = in.readString();
        emojis = in.createStringArrayList();
        accessibilityText = in.readString();
        size = in.readLong();
    }

    public static final Creator<Sticker> CREATOR = new Creator<Sticker>() {
        @Override
        public Sticker createFromParcel(Parcel in) {
            return new Sticker(in);
        }

        @Override
        public Sticker[] newArray(int size) {
            return new Sticker[size];
        }
    };

    public void setSize(long size) {
        this.size = size;
    }

    public void setEmojis(List<String> emojis) {
        this.emojis = emojis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(imageFileName);
        dest.writeStringList(emojis);
        dest.writeString(accessibilityText);
        dest.writeLong(size);
    }
}
