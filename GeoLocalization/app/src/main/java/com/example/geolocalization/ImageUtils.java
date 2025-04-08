package com.example.geolocalization;

import android.graphics.Bitmap;
import org.opencv.android.Utils; // Import the Utils class from OpenCV
import org.opencv.core.CvType;
import org.opencv.core.Mat;

public class ImageUtils {
    public static Mat bitmapToMat(Bitmap bitmap) {
        Mat mat = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC4);
        // Convert Bitmap to Mat
        Utils.bitmapToMat(bitmap, mat); // Use the Utils class to convert
        return mat;
    }
}