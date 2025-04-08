package com.example.geolocalization;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.location.Address;
import android.location.Geocoder;
//imoprt android.Manifest;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.Manifest;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.SIFT;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int SELECT_TEST_IMAGE = 1;
    private static final int SELECT_TRAINING_IMAGES = 2;
    private static final int MATCH_THRESHOLD = 10;

    private Bitmap testImage;
    private List<Bitmap> trainingImages = new ArrayList<>();
    private double[] testImageLocation = new double[2];
    private double estimatedLat = 0.0, estimatedLon = 0.0;
    private int validImageCount = 0;
    private List<double[]> trainingImageLocations = new ArrayList<>();

    private ImageView matchesImageView;
    private TextView testImageResult, trainingImageResult, matchingScoreResult, thresholdResult, estimatedLocationResult, distanceResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!OpenCVLoader.initDebug()) {
            updateMessage(testImageResult, "OpenCV Initialization Failed!");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION}, 1);
        }

        Button selectTestImage = findViewById(R.id.selectTestImage);
        Button selectTrainingImages = findViewById(R.id.selectTrainingImages);
        Button findMatchingPoints = findViewById(R.id.applySiftWeighting);
        Button checkThreshold = findViewById(R.id.checkThreshold);
        Button estimateLocation = findViewById(R.id.estimateLocation);
        Button calculateDistance = findViewById(R.id.calculateDistance);

        matchesImageView = findViewById(R.id.matchesImageView);

        testImageResult = findViewById(R.id.selectTestImageResult);
        trainingImageResult = findViewById(R.id.selectTrainingImagesResult);
        matchingScoreResult = findViewById(R.id.applySiftWeightingResult);
        thresholdResult = findViewById(R.id.thresholdResult);
        estimatedLocationResult = findViewById(R.id.estimatedLocationResult);
        distanceResult = findViewById(R.id.distanceResult);

        selectTestImage.setOnClickListener(v -> selectImage(SELECT_TEST_IMAGE));
        selectTrainingImages.setOnClickListener(v -> selectImages(SELECT_TRAINING_IMAGES));
        findMatchingPoints.setOnClickListener(v -> matchImages());
        checkThreshold.setOnClickListener(v -> checkThreshold());
        estimateLocation.setOnClickListener(v -> estimateLocation());
        calculateDistance.setOnClickListener(v -> calculateDistance());
    }

    private void updateMessage(TextView textView, String message) {
        textView.setText(message);
    }

    private void selectImage(int requestCode) {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Picture"), requestCode);
    }

    private void selectImages(int requestCode) {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Pictures"), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == SELECT_TEST_IMAGE) {
                Uri selectedImageUri = data.getData();
                testImage = convertUriToBitmap(selectedImageUri);
                testImageLocation = fetchLocation(selectedImageUri);
                updateMessage(testImageResult, "Test Image Location: Lat " + testImageLocation[0] + ", Lon " + testImageLocation[1]);
            }
            else if (requestCode == SELECT_TRAINING_IMAGES) {
                updateMessage(trainingImageResult, "Processing..."); // Show "Processing..."

                trainingImages.clear();
                trainingImageLocations.clear();
                StringBuilder locationsInfo = new StringBuilder();

                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        Uri imageUri = data.getClipData().getItemAt(i).getUri();
                        trainingImages.add(convertUriToBitmap(imageUri));
                        double[] location = fetchLocation(imageUri);
                        trainingImageLocations.add(location);
                        locationsInfo.append("Training Image ").append(i + 1).append(" Location: Lat ")
                                .append(location[0]).append(", Lon ").append(location[1]).append("\n");
                    }
                } else if (data.getData() != null) {
                    Uri imageUri = data.getData();
                    trainingImages.add(convertUriToBitmap(imageUri));
                    double[] location = fetchLocation(imageUri);
                    trainingImageLocations.add(location);
                    locationsInfo.append("Training Image 1 Location: Lat ")
                            .append(location[0]).append(", Lon ").append(location[1]).append("\n");
                }

                // Display count and locations
                updateMessage(trainingImageResult, "Training Images Loaded: " + trainingImages.size() + "\n" + locationsInfo.toString());
            }
        }
    }



    private Bitmap convertUriToBitmap(Uri uri) {
        try {
            return MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private double[] fetchLocation(Uri imageUri) {
        try {
            // Get absolute file path from Uri
            String imagePath = getRealPathFromURI(imageUri);
            if (imagePath == null) return new double[]{0.0, 0.0};

            // Read Exif data from file
            ExifInterface exif = new ExifInterface(imagePath);
            float[] latLong = new float[2];

            if (exif.getLatLong(latLong)) {
                Log.d("GeoLocalization", "Image GPS: Lat " + latLong[0] + ", Lon " + latLong[1]);
                return new double[]{latLong[0], latLong[1]};  // Return actual GPS coordinates
            } else {
                Log.d("GeoLocalization", "No GPS metadata found in image.");
                return new double[]{0.0, 0.0};  // No GPS found
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("GeoLocalization", "Error reading image metadata.");
            return new double[]{0.0, 0.0};  // Return default in case of error
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, continue
            } else {
                updateMessage(testImageResult, "Storage permission required to fetch image location!");
            }
        }
    }

    private String getRealPathFromURI(Uri contentUri) {
        String result = null;
        Cursor cursor = getContentResolver().query(contentUri, null, null, null, null);
        if (cursor != null) {
            int column_index = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
            if (column_index != -1) {
                cursor.moveToFirst();
                result = cursor.getString(column_index);
            }
            cursor.close();
        }
        return result;
    }


    private void checkThreshold() {
        if (testImage == null || trainingImages.isEmpty()) {
            updateMessage(thresholdResult, "Select a Test Image and Training Images First!");
            return;
        }

        updateMessage(thresholdResult, "Processing...");

        new Thread(() -> {
            long startTime = System.currentTimeMillis(); // Start time

            Mat testMat = ImageUtils.bitmapToMat(testImage);
            StringBuilder matchResults = new StringBuilder();
            matchResults.append("Threshold: ").append(MATCH_THRESHOLD).append("\n");

            boolean foundValidMatches = false;

            for (int i = 0; i < trainingImages.size(); i++) {
                Mat trainMat = ImageUtils.bitmapToMat(trainingImages.get(i));
                int matchScore = matchSiftFeatures(testMat, trainMat);

                if (matchScore >= MATCH_THRESHOLD) {
                    foundValidMatches = true;
                    matchResults.append("Image ").append(i + 1).append(": Score ").append(matchScore).append("\n");
                }
            }

            long endTime = System.currentTimeMillis();
            long elapsedTime = endTime - startTime;

            final boolean finalFoundMatches = foundValidMatches; // Store as final variable
            final String finalMessage = finalFoundMatches
                    ? matchResults.toString() + "\nTime Consumed: " + elapsedTime + " ms"
                    : "No matches found above threshold (" + MATCH_THRESHOLD + ").\nTime Consumed: " + elapsedTime + " ms";

            runOnUiThread(() -> updateMessage(thresholdResult, finalMessage));
        }).start();
    }


    private void matchImages() {
        if (testImage == null || trainingImages.isEmpty()) {
            updateMessage(matchingScoreResult, "Select a Test Image and Training Images First!");
            return;
        }

        updateMessage(matchingScoreResult, "Processing...");

        new Thread(() -> {
            long startTime = System.currentTimeMillis(); // Start time

            Mat testMat = ImageUtils.bitmapToMat(testImage);
            StringBuilder scores = new StringBuilder();

            for (int i = 0; i < trainingImages.size(); i++) {
                Mat trainMat = ImageUtils.bitmapToMat(trainingImages.get(i));
                int matchCount = matchSiftFeatures(testMat, trainMat);
                scores.append("Score ").append(i + 1).append(": ").append(matchCount).append("\n");
            }

            long endTime = System.currentTimeMillis();
            long elapsedTime = endTime - startTime;

            final String finalScores = scores.toString() + "\nTime Consumed: " + elapsedTime + " ms";

            runOnUiThread(() -> updateMessage(matchingScoreResult, finalScores));
        }).start();
    }

    private class MatchImagesTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... voids) {
            Mat testMat = ImageUtils.bitmapToMat(testImage);
            StringBuilder scores = new StringBuilder();

            for (int i = 0; i < trainingImages.size(); i++) {
                Mat trainMat = ImageUtils.bitmapToMat(trainingImages.get(i));
                int matchCount = matchSiftFeatures(testMat, trainMat);
                scores.append("Score ").append(i + 1).append(": ").append(matchCount).append("\n");
            }

            return scores.toString();
        }

        @Override
        protected void onPostExecute(String result) {
            updateMessage(matchingScoreResult, result);
        }
    }


    private int matchSiftFeatures(Mat testMat, Mat trainMat) {
        SIFT sift = SIFT.create(1000); // Limit keypoints (To reduce processing time and optimizes speed)

        // Detect keypoints and compute descriptors for test image
        MatOfKeyPoint keypointsTest = new MatOfKeyPoint();
        Mat descriptorsTest = new Mat();
        sift.detectAndCompute(testMat, new Mat(), keypointsTest, descriptorsTest);

        // Detect keypoints and compute descriptors for training image
        MatOfKeyPoint keypointsTrain = new MatOfKeyPoint();
        Mat descriptorsTrain = new Mat();
        sift.detectAndCompute(trainMat, new Mat(), keypointsTrain, descriptorsTrain);

        // Use Brute-Force Matcher with Hamming Distance
        DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE);
        List<MatOfDMatch> knnMatches = new ArrayList<>();
        matcher.knnMatch(descriptorsTest, descriptorsTrain, knnMatches, 2); // Use KNN matching

        // Apply Lowe's ratio test to filter good matches
        List<DMatch> goodMatches = new ArrayList<>();
        for (MatOfDMatch mat : knnMatches) {
            DMatch[] matchesArray = mat.toArray();
            if (matchesArray.length == 2) {
                if (matchesArray[0].distance < 0.75 * matchesArray[1].distance) {
                    goodMatches.add(matchesArray[0]);
                }
            }
        }

        return goodMatches.size(); // Return the number of matching keypoints
    }



    private void estimateLocation() {
        if (testImage == null || trainingImages.isEmpty()) {
            updateMessage(estimatedLocationResult, "Select a Test Image and Training Images First!");
            return;
        }

        updateMessage(estimatedLocationResult, "Processing...");

        new Thread(() -> {
            long startTime = System.currentTimeMillis();

            Mat testMat = ImageUtils.bitmapToMat(testImage);
            double sumLat = 0, sumLon = 0;
            int count = 0;
            StringBuilder usedImages = new StringBuilder();

            for (int i = 0; i < trainingImages.size(); i++) {
                Mat trainMat = ImageUtils.bitmapToMat(trainingImages.get(i));
                int matchCount = matchSiftFeatures(testMat, trainMat);

                if (matchCount >= MATCH_THRESHOLD) {
                    double[] location = trainingImageLocations.get(i);
                    sumLat += location[0];
                    sumLon += location[1];
                    count++;
                    usedImages.append("Image ").append(i + 1).append(" used (Score: ").append(matchCount).append(")\n");
                }
            }

            long endTime = System.currentTimeMillis();
            long elapsedTime = endTime - startTime;

            final int finalCount = count;
            final double finalLat = finalCount == 0 ? 0.0 : sumLat / finalCount;
            final double finalLon = finalCount == 0 ? 0.0 : sumLon / finalCount;

            final String finalMessage = finalCount == 0
                    ? "No image passed the threshold. Unable to estimate location.\nTime Consumed: " + elapsedTime + " ms"
                    : "Estimated Location: Lat " + finalLat + ", Lon " + finalLon + "\n" + usedImages.toString() +
                    "\nTime Consumed: " + elapsedTime + " ms";

            runOnUiThread(() -> updateMessage(estimatedLocationResult, finalMessage));
        }).start();
    }


    private void calculateDistance() {
        if (testImage == null || trainingImages.isEmpty()) {
            updateMessage(distanceResult, "Select a Test Image and Training Images First!");
            return;
        }

        if (validImageCount == 0) {
            updateMessage(distanceResult, "Cannot calculate distance. No estimated location available.");
            return;
        }

        updateMessage(distanceResult, "Processing...");

        new Thread(() -> {
            long startTime = System.currentTimeMillis();

            double roundedTestLat = Math.round(testImageLocation[0] * 1e6) / 1e6;
            double roundedTestLon = Math.round(testImageLocation[1] * 1e6) / 1e6;
            double roundedEstLat = estimatedLat;
            double roundedEstLon = estimatedLon;

            double distance = (Math.abs(roundedTestLat - roundedEstLat) < 1e-6 &&
                    Math.abs(roundedTestLon - roundedEstLon) < 1e-6)
                    ? 0.0
                    : calculateHaversineDistance(
                    new double[]{roundedTestLat, roundedTestLon},
                    new double[]{roundedEstLat, roundedEstLon}
            );

            long endTime = System.currentTimeMillis();
            long elapsedTime = endTime - startTime;

            final String finalMessage = "Distance: " + distance + " meters\nTime Consumed: " + elapsedTime + " ms";

            runOnUiThread(() -> updateMessage(distanceResult, finalMessage));
        }).start();
    }






    // Haversine formula to calculate real-world distance in meters
    private double calculateHaversineDistance(double[] point1, double[] point2) {
        final double R = 6371000; // Earth's radius in meters
        double lat1 = Math.toRadians(point1[0]);
        double lon1 = Math.toRadians(point1[1]);
        double lat2 = Math.toRadians(point2[0]);
        double lon2 = Math.toRadians(point2[1]);

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c; // Distance in meters
    }
}
