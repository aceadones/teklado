/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.dictionary;

import com.android.inputmethod.latin.BinaryDictionary;

import helium314.keyboard.latin.NgramContext;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ComposedData;
import helium314.keyboard.latin.makedict.WordProperty;
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This class provides binary dictionary reading operations with locking. An instance of this class
 * can be used by multiple threads. Note that different session IDs must be used when multiple
 * threads get suggestions using this class.
 */
public final class ReadOnlyBinaryDictionary extends Dictionary {
    /**
     * A lock for accessing binary dictionary. Only closing binary dictionary is the operation
     * that change the state of dictionary.
     */
    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();

    private final BinaryDictionary mBinaryDictionary;

    public ReadOnlyBinaryDictionary(final String filename, final long offset, final long length,
            final boolean useFullEditDistance, final Locale locale, final String dictType) {
        super(dictType, locale);
        mBinaryDictionary = new BinaryDictionary(filename, offset, length, useFullEditDistance,
                locale, dictType, false /* isUpdatable */);
    }

    public boolean isValidDictionary() {
        return !mBinaryDictionary.isCorrupted();
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final ComposedData composedData,
            final NgramContext ngramContext, final long proximityInfoHandle,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final int sessionId, final float weightForLocale,
            final float[] inOutWeightOfLangModelVsSpatialModel) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getSuggestions(composedData, ngramContext,
                        proximityInfoHandle, settingsValuesForSuggestion, sessionId,
                        weightForLocale, inOutWeightOfLangModelVsSpatialModel);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return null;
    }

    @Override
    public boolean isInDictionary(final String word) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.isInDictionary(word);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return false;
    }

    @Override
    public boolean shouldAutoCommit(final SuggestedWordInfo candidate) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.shouldAutoCommit(candidate);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return false;
    }

    @Override
    public int getFrequency(final String word) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getFrequency(word);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return NOT_A_PROBABILITY;
    }

    @Override
    public int getMaxFrequencyOfExactMatches(final String word) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getMaxFrequencyOfExactMatches(word);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return NOT_A_PROBABILITY;
    }

    @Override
    public void forEachUnigram(final Dictionary.UnigramVisitor visitor) {
        if (visitor == null || !mLock.readLock().tryLock()) return;
        try {
            if (!mBinaryDictionary.isValidDictionary()) return;
            int token = 0;
            int guard = 0;
            while (guard++ < MAX_UNIGRAMS) {
                final BinaryDictionary.GetNextWordPropertyResult result =
                        mBinaryDictionary.getNextWordProperty(token);
                if (result == null || result.mWordProperty == null) return;
                if (visitor.onWord(result.mWordProperty, mDictType, mLocale)) return;
                final int next = result.mNextToken;
                if (next == token) return;
                token = next;
                if (token == 0) return;
            }
        } finally {
            mLock.readLock().unlock();
        }
    }

    private static final int MAX_UNIGRAMS = 400000;

    @Override
    public WordProperty getWordProperty(String word, boolean isBeginningOfSentence) {
        if (mLock.readLock().tryLock()) {
            try {
                return mBinaryDictionary.getWordProperty(word, isBeginningOfSentence);
            } finally {
                mLock.readLock().unlock();
            }
        }
        return null;
    }

    @Override
    public void close() {
        mLock.writeLock().lock();
        try {
            mBinaryDictionary.close();
        } finally {
            mLock.writeLock().unlock();
        }
    }

    public String getHash() {
        return mBinaryDictionary.getHash();
    }
}
