/**
 * Scanner Screen - Camera capture + card identification UI.
 * Main scanning workflow:
 * 1. Show camera preview
 * 2. User taps capture (or uses auto-capture later)
 * 3. OCR identifies card
 * 4. High confidence → auto-accept + show confirmation
 * 5. Medium → show candidates for selection
 * 6. Low → show manual search option
 */

import React, { useRef, useState, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Image,
  FlatList,
  ActivityIndicator,
  Alert,
} from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useScannerStore } from '../store/scannerStore';
import { recognizeCard } from '../services/recognition';
import { acceptScan, undoLastScan, getStats } from '../services/inventory';
import type { ScanCandidate } from '../types/scan';
import { manualAddCard } from '../services/inventory';

export default function ScannerScreen() {
  const cameraRef = useRef<CameraView>(null);
  const [permission, requestPermission] = useCameraPermissions();
  const [capturedImage, setCapturedImage] = useState<string | null>(null);

  const {
    state,
    lastResult,
    lastMessage,
    totalScanned,
    uniqueCards,
    lastQuantity,
    setState,
    setResult,
    setMessage,
    setStats,
    setLastQuantity,
    reset,
  } = useScannerStore();

  // Load stats on mount
  useEffect(() => {
    loadStats();
  }, []);

  const loadStats = async () => {
    const stats = await getStats();
    setStats(stats.totalCards, stats.uniqueCards);
  };

  // Handle capture
  const handleCapture = useCallback(async () => {
    if (!cameraRef.current || state === 'CAPTURING' || state === 'IDENTIFYING') return;

    setState('CAPTURING');

    try {
      const photo = await cameraRef.current.takePictureAsync({
        quality: 0.8,
        skipProcessing: false,
      });

      if (!photo?.uri) {
        setState('ERROR');
        setMessage('Failed to capture photo');
        return;
      }

      setCapturedImage(photo.uri);
      setState('IDENTIFYING');

      // Run recognition
      const result = await recognizeCard(photo.uri);
      setResult(result);

      // Auto-accept high confidence
      if (result.confidence === 'HIGH' && result.cardId) {
        const addResult = await acceptScan(result);
        if (addResult.success) {
          setMessage(addResult.message);
          setLastQuantity(addResult.quantity);
          await loadStats();
          // Auto-reset after showing confirmation briefly
          setTimeout(() => {
            setCapturedImage(null);
            reset();
          }, 1500);
        }
      }
    } catch (error) {
      console.error('[Scanner] Capture error:', error);
      setState('ERROR');
      setMessage('Capture failed. Try again.');
    }
  }, [state]);

  // Handle candidate selection
  const handleSelectCandidate = useCallback(
    async (candidate: ScanCandidate) => {
      const addResult = await manualAddCard(
        candidate.cardId,
        candidate.name,
        candidate.setName,
        candidate.collectorNumber
      );
      if (addResult.success) {
        setMessage(addResult.message);
        setLastQuantity(addResult.quantity);
        await loadStats();
        setTimeout(() => {
          setCapturedImage(null);
          reset();
        }, 1500);
      }
    },
    []
  );

  // Handle undo
  const handleUndo = useCallback(async () => {
    const result = await undoLastScan();
    setMessage(result.message);
    await loadStats();
  }, []);

  // Permission not granted
  if (!permission) {
    return (
      <View style={styles.container}>
        <ActivityIndicator size="large" color="#ffcb05" />
      </View>
    );
  }

  if (!permission.granted) {
    return (
      <View style={styles.container}>
        <Text style={styles.permissionText}>Camera access is required to scan cards.</Text>
        <TouchableOpacity style={styles.button} onPress={requestPermission}>
          <Text style={styles.buttonText}>Grant Permission</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* Stats Bar */}
      <View style={styles.statsBar}>
        <Text style={styles.statText}>Total: {totalScanned}</Text>
        <Text style={styles.statText}>Unique: {uniqueCards}</Text>
        <Text style={styles.statusText}>
          {state === 'IDLE' && '📷 Ready'}
          {state === 'CAPTURING' && '📸 Capturing...'}
          {state === 'IDENTIFYING' && '🔍 Identifying...'}
          {state === 'RESULT' && '✅ Result'}
          {state === 'ERROR' && '❌ Error'}
        </Text>
      </View>

      {/* Camera or Captured Image */}
      <View style={styles.cameraContainer}>
        {capturedImage && state !== 'IDLE' ? (
          <Image source={{ uri: capturedImage }} style={styles.capturedImage} />
        ) : (
          <CameraView
            ref={cameraRef}
            style={styles.camera}
            facing="back"
          >
            {/* Card alignment guide */}
            <View style={styles.cardGuide}>
              <View style={styles.cardGuideInner} />
            </View>
          </CameraView>
        )}
      </View>

      {/* Result Area */}
      <View style={styles.resultArea}>
        {/* Identification in progress */}
        {state === 'IDENTIFYING' && (
          <View style={styles.identifyingBox}>
            <ActivityIndicator size="small" color="#ffcb05" />
            <Text style={styles.identifyingText}>Identifying card...</Text>
          </View>
        )}

        {/* High confidence result (auto-accepted) */}
        {state === 'RESULT' && lastResult?.confidence === 'HIGH' && (
          <View style={styles.resultBox}>
            <Text style={styles.resultName}>{lastResult.name}</Text>
            <Text style={styles.resultSet}>
              {lastResult.setName} {lastResult.collectorNumber}
            </Text>
            <Text style={styles.resultQty}>Qty: {lastQuantity}</Text>
            <Text style={styles.confidenceHigh}>✓ HIGH CONFIDENCE</Text>
          </View>
        )}

        {/* Medium/Low confidence - show candidates */}
        {state === 'RESULT' &&
          lastResult &&
          lastResult.confidence !== 'HIGH' &&
          lastResult.candidates.length > 0 && (
            <View style={styles.candidatesBox}>
              <Text style={styles.candidatesTitle}>
                {lastResult.confidence === 'MEDIUM'
                  ? 'Likely matches — tap to confirm:'
                  : 'Uncertain — select the correct card:'}
              </Text>
              <FlatList
                data={lastResult.candidates.slice(0, 5)}
                keyExtractor={(item) => item.cardId}
                renderItem={({ item, index }) => (
                  <TouchableOpacity
                    style={styles.candidateRow}
                    onPress={() => handleSelectCandidate(item)}
                  >
                    <Text style={styles.candidateIndex}>{index + 1}</Text>
                    {item.imageUrl ? (
                      <Image
                        source={{ uri: item.imageUrl }}
                        style={styles.candidateImage}
                      />
                    ) : (
                      <View style={styles.candidateImagePlaceholder} />
                    )}
                    <View style={styles.candidateInfo}>
                      <Text style={styles.candidateName}>{item.name}</Text>
                      <Text style={styles.candidateSet}>
                        {item.setName} {item.collectorNumber}
                      </Text>
                    </View>
                    <Text style={styles.candidateScore}>
                      {Math.round(item.confidenceScore * 100)}%
                    </Text>
                  </TouchableOpacity>
                )}
              />
              <TouchableOpacity
                style={styles.skipButton}
                onPress={() => {
                  setCapturedImage(null);
                  reset();
                }}
              >
                <Text style={styles.skipButtonText}>Skip / Retake</Text>
              </TouchableOpacity>
            </View>
          )}

        {/* No candidates found */}
        {state === 'RESULT' &&
          lastResult &&
          !lastResult.cardId &&
          lastResult.candidates.length === 0 && (
            <View style={styles.noResultBox}>
              <Text style={styles.noResultText}>Could not identify card.</Text>
              <Text style={styles.noResultSubtext}>
                OCR read: "{lastResult.ocrName}" / "{lastResult.ocrNumber}"
              </Text>
              <TouchableOpacity
                style={styles.button}
                onPress={() => {
                  setCapturedImage(null);
                  reset();
                }}
              >
                <Text style={styles.buttonText}>Try Again</Text>
              </TouchableOpacity>
            </View>
          )}

        {/* Message */}
        {lastMessage && state === 'IDLE' && (
          <Text style={styles.messageText}>{lastMessage}</Text>
        )}

        {/* Error */}
        {state === 'ERROR' && (
          <View style={styles.errorBox}>
            <Text style={styles.errorText}>{lastMessage || 'An error occurred'}</Text>
            <TouchableOpacity
              style={styles.button}
              onPress={() => {
                setCapturedImage(null);
                reset();
              }}
            >
              <Text style={styles.buttonText}>Dismiss</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>

      {/* Bottom Controls */}
      <View style={styles.controls}>
        <TouchableOpacity style={styles.undoButton} onPress={handleUndo}>
          <Text style={styles.undoText}>↩ Undo</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[
            styles.captureButton,
            (state === 'CAPTURING' || state === 'IDENTIFYING') && styles.captureDisabled,
          ]}
          onPress={handleCapture}
          disabled={state === 'CAPTURING' || state === 'IDENTIFYING'}
        >
          <View style={styles.captureInner} />
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.resetButton}
          onPress={() => {
            setCapturedImage(null);
            reset();
          }}
        >
          <Text style={styles.resetText}>✕ Clear</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a2e',
  },
  statsBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: '#16213e',
  },
  statText: {
    color: '#a0a0a0',
    fontSize: 13,
    fontWeight: '600',
  },
  statusText: {
    color: '#ffcb05',
    fontSize: 13,
    fontWeight: '600',
  },
  cameraContainer: {
    flex: 1,
    maxHeight: '45%',
  },
  camera: {
    flex: 1,
  },
  capturedImage: {
    flex: 1,
    resizeMode: 'contain',
  },
  cardGuide: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  cardGuideInner: {
    width: '70%',
    aspectRatio: 2.5 / 3.5, // Standard card ratio
    borderWidth: 2,
    borderColor: 'rgba(255, 203, 5, 0.6)',
    borderRadius: 12,
    borderStyle: 'dashed',
  },
  resultArea: {
    flex: 1,
    padding: 12,
  },
  identifyingBox: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 16,
  },
  identifyingText: {
    color: '#fff',
    marginLeft: 12,
    fontSize: 16,
  },
  resultBox: {
    backgroundColor: '#0f3460',
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#4caf50',
  },
  resultName: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
  },
  resultSet: {
    color: '#a0a0a0',
    fontSize: 14,
    marginTop: 4,
  },
  resultQty: {
    color: '#ffcb05',
    fontSize: 20,
    fontWeight: 'bold',
    marginTop: 8,
  },
  confidenceHigh: {
    color: '#4caf50',
    fontSize: 12,
    marginTop: 8,
    fontWeight: '600',
  },
  candidatesBox: {
    flex: 1,
  },
  candidatesTitle: {
    color: '#ffcb05',
    fontSize: 14,
    fontWeight: '600',
    marginBottom: 8,
  },
  candidateRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#0f3460',
    borderRadius: 8,
    padding: 10,
    marginBottom: 6,
  },
  candidateIndex: {
    color: '#ffcb05',
    fontSize: 16,
    fontWeight: 'bold',
    width: 24,
  },
  candidateImage: {
    width: 36,
    height: 50,
    borderRadius: 4,
    marginRight: 10,
  },
  candidateImagePlaceholder: {
    width: 36,
    height: 50,
    borderRadius: 4,
    marginRight: 10,
    backgroundColor: '#333',
  },
  candidateInfo: {
    flex: 1,
  },
  candidateName: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  candidateSet: {
    color: '#a0a0a0',
    fontSize: 12,
    marginTop: 2,
  },
  candidateScore: {
    color: '#ffcb05',
    fontSize: 14,
    fontWeight: '600',
  },
  skipButton: {
    alignSelf: 'center',
    paddingVertical: 10,
    paddingHorizontal: 20,
    marginTop: 8,
  },
  skipButtonText: {
    color: '#a0a0a0',
    fontSize: 14,
  },
  noResultBox: {
    alignItems: 'center',
    padding: 16,
  },
  noResultText: {
    color: '#ff6b6b',
    fontSize: 16,
    fontWeight: '600',
  },
  noResultSubtext: {
    color: '#a0a0a0',
    fontSize: 12,
    marginTop: 8,
    marginBottom: 16,
  },
  messageText: {
    color: '#4caf50',
    fontSize: 14,
    textAlign: 'center',
    padding: 8,
  },
  errorBox: {
    alignItems: 'center',
    padding: 16,
  },
  errorText: {
    color: '#ff6b6b',
    fontSize: 14,
    marginBottom: 12,
  },
  controls: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'center',
    paddingVertical: 16,
    paddingHorizontal: 24,
    backgroundColor: '#16213e',
  },
  captureButton: {
    width: 72,
    height: 72,
    borderRadius: 36,
    borderWidth: 4,
    borderColor: '#ffcb05',
    justifyContent: 'center',
    alignItems: 'center',
  },
  captureDisabled: {
    borderColor: '#555',
  },
  captureInner: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#ffcb05',
  },
  undoButton: {
    padding: 12,
  },
  undoText: {
    color: '#a0a0a0',
    fontSize: 14,
  },
  resetButton: {
    padding: 12,
  },
  resetText: {
    color: '#a0a0a0',
    fontSize: 14,
  },
  permissionText: {
    color: '#fff',
    fontSize: 16,
    textAlign: 'center',
    padding: 24,
  },
  button: {
    backgroundColor: '#ffcb05',
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 8,
  },
  buttonText: {
    color: '#1a1a2e',
    fontSize: 16,
    fontWeight: 'bold',
  },
});
