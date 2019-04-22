package program;

import com.microsoft.azure.cognitiveservices.vision.customvision.prediction.CustomVisionPredictionManager;
import com.microsoft.azure.cognitiveservices.vision.customvision.prediction.PredictionEndpoint;
import com.microsoft.azure.cognitiveservices.vision.customvision.prediction.models.ImagePrediction;
import com.microsoft.azure.cognitiveservices.vision.customvision.prediction.models.Prediction;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.StorageAccount;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.OperationContext;
import com.microsoft.azure.storage.blob.*;
import com.microsoft.azure.storage.queue.CloudQueue;
import com.microsoft.azure.storage.queue.CloudQueueClient;
import com.microsoft.azure.storage.queue.CloudQueueMessage;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.UUID;


/**
 * Azure Functions with Azure Blob trigger.
 */
public class ProcessImage {

    private static final String subscriptionKey = "3733733beb404b4cbebbfc87ba1f19bd";

    private static final String visionUriBase = "https://southcentralus.api.cognitive.microsoft.com/vision/v2.0/ocr";

    private static final String imageToAnalyze = "https://fastlaneblobs.blob.core.windows.net/kvish6-pending-validation/test1.jpg";

    private static final String storageConnectionString ="DefaultEndpointsProtocol=https;AccountName=fastlaneblobs;AccountKey=CeGaoYc/cwpcf44WQpgEP61ERm8cQGwe364M1zaPimeUEOGNVCXZktPmYUBfq9zl5YIFBQz0bB6/Sz/cD2OvJA==;EndpointSuffix=core.windows.net";

    private static String blobName;

    private static File rawImage;

    private static File croppedImagePath;

    private static String uploadedFileURL;

    private static boolean moveToErrorHandling;

    /**
     * This function will be invoked when a new or updated blob is detected at the specified path. The blob contents are provided as input to this function.
     */
    @FunctionName("ProcessImage")
    @StorageAccount("DefaultEndpointsProtocol=https;AccountName=fastlaneblobs;AccountKey=CeGaoYc/cwpcf44WQpgEP61ERm8cQGwe364M1zaPimeUEOGNVCXZktPmYUBfq9zl5YIFBQz0bB6/Sz/cD2OvJA==;EndpointSuffix=core.windows.net")
    public void run(
        @BlobTrigger(name = "content", path = "kvish6-pending-validation/{name}", dataType = "binary", connection ="DefaultEndpointsProtocol=https;AccountName=fastlaneblobs;AccountKey=CeGaoYc/cwpcf44WQpgEP61ERm8cQGwe364M1zaPimeUEOGNVCXZktPmYUBfq9zl5YIFBQz0bB6/Sz/cD2OvJA==;EndpointSuffix=core.windows.net") byte[] content,
        @BindingName("name") String name,
        final ExecutionContext context
    )
    {
        context.getLogger().info("Java Blob trigger function processed a blob. Name: " + name + "\n  Size: " + content.length + " Bytes");
        try{
            blobName = FilenameUtils.getName(new URL(imageToAnalyze).getPath());
            moveToErrorHandling = false;
            rawImage = new File("/Users/ohada/dev/comazurefastlane/raw.jpg");
            croppedImagePath = new File("/Users/ohada/dev/comazurefastlane/test1-after-crop.jpg");
            downloadFile();
            Prediction predicition = makePredictionRequest();
            cropImageAfterPrediction(rawImage,predicition);
            uploadCropedImageToBlobStorage();
            if(moveToErrorHandling) {
                writeToErrorQueueAndMoveBlob();
            }
            else{
                makeOCRRequest();
            }
            if(moveToErrorHandling) {
                writeToErrorQueueAndMoveBlob();
            }
            else{
                writeEntryToDB();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeEntryToDB(){

    }

    private static void writeToErrorQueueAndMoveBlob(){

        try
        {
            CloudStorageAccount storageAccount = CloudStorageAccount.parse(storageConnectionString);

            CloudQueueClient queueClient = storageAccount.createCloudQueueClient();

            CloudQueue queue = queueClient.getQueueReference("kvish6-validation-error");

            queue.createIfNotExists();

            CloudQueueMessage message = new CloudQueueMessage(blobName);
            queue.addMessage(message);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

    }

    private static void uploadCropedImageToBlobStorage(){
        CloudStorageAccount storageAccount;
        CloudBlobClient blobClient = null;
        CloudBlobContainer container=null;

        try{
            storageAccount = CloudStorageAccount.parse(storageConnectionString);
            blobClient = storageAccount.createCloudBlobClient();
            container = blobClient.getContainerReference("kvish6-cropped");

            container.createIfNotExists(BlobContainerPublicAccessType.CONTAINER, new BlobRequestOptions(), new OperationContext());

            //Getting a blob reference
            CloudBlockBlob blob = container.getBlockBlobReference(croppedImagePath.getName());
            uploadedFileURL = blob.getUri().toString();
            //Creating blob and uploading file to it
            System.out.println("Uploading the cropped file ");
            blob.uploadFromFile(croppedImagePath.getAbsolutePath());
        }
        catch(Exception e){
            moveToErrorHandling = true;
        }
    }

    private static void cropImageAfterPrediction(File fileToCrop, Prediction prediction){
        try {
            BufferedImage bufferedImage = ImageIO.read(fileToCrop);
            System.out.println("Original Image Dimension: "+bufferedImage.getWidth()+"x"+bufferedImage.getHeight());

            BufferedImage croppedImage = bufferedImage.getSubimage((int)(prediction.boundingBox().left()* 1000.0f)-50, (int)(prediction.boundingBox().top()* 1000.0f)-50, (int)(prediction.boundingBox().width()* 1000.0f), (int)(prediction.boundingBox().height()* 1000.0f));

            System.out.println("Cropped Image Dimension: "+croppedImage.getWidth()+"x"+croppedImage.getHeight());

            ImageIO.write(croppedImage,"jpg", croppedImagePath);

            System.out.println("Image cropped successfully: "+croppedImagePath.getPath());

        } catch (IOException e) {
            moveToErrorHandling = true;
        }

    }

    private static void downloadFile() {
        try {
            FileUtils.copyURLToFile(new URL(imageToAnalyze), rawImage);
        } catch (IOException e) {
            moveToErrorHandling = true;
        }

    }

    private static Prediction makePredictionRequest() {
        final String predictionApiKey = "75e433a9916d46139fa9be17285c22ff";
        final UUID projectID = UUID.fromString("067b567e-6b0c-4aa4-86b7-a770dae9e92c");
        final UUID iterationID = UUID.fromString("6a3314bd-cd6a-40b5-b37a-7a75620332a4");

        PredictionEndpoint predictClient = CustomVisionPredictionManager.authenticate(predictionApiKey);


        byte[] bytesArray = new byte[(int) rawImage.length()];

        try {

            //init array with file length

            FileInputStream fis = new FileInputStream(rawImage);
            fis.read(bytesArray); //read file into bytes[]
            fis.close();
        } catch (IOException e) {
            moveToErrorHandling = true;
        }

        byte[] testImage = bytesArray;

        ImagePrediction results = predictClient.predictions().predictImage()
                .withProjectId(projectID)
                .withImageData(testImage)
                .withIterationId(iterationID)
                .execute();

        Prediction highestScorePrediction = null;
        Prediction currentPrediction = new Prediction();

        for (Prediction prediction: results.predictions()) {

            if (prediction.tagName().equals("Registration Number")){
                currentPrediction = prediction;

                if (highestScorePrediction != null) {

                    if (currentPrediction.probability() * 100.0f >= highestScorePrediction.probability() * 100.0f) {
                        highestScorePrediction = currentPrediction;
                    }

                } else {
                    highestScorePrediction = currentPrediction;
                }
            }
        }
        System.out.println(String.format("\t%s: %.0f%% at: %.0f, %.0f, %.0f, %.0f",
                highestScorePrediction.tagName(),
                highestScorePrediction.probability() * 100.0f,
                highestScorePrediction.boundingBox().left() * 1000 ,
                highestScorePrediction.boundingBox().top() * 1000 ,
                highestScorePrediction.boundingBox().width() * 1000 ,
                highestScorePrediction.boundingBox().height() * 1000
        ));

        return highestScorePrediction;
    }

    private static void makeOCRRequest() {
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();

        try {
            URIBuilder uriBuilder = new URIBuilder(visionUriBase);

            uriBuilder.setParameter("language", "unk");
            uriBuilder.setParameter("detectOrientation", "true");

            URI uri = uriBuilder.build();
            HttpPost request = new HttpPost(uri);

            request.setHeader("Content-Type", "application/json");
            request.setHeader("Ocp-Apim-Subscription-Key", subscriptionKey);

            StringEntity requestEntity =
                    new StringEntity("{\"url\":\"" + uploadedFileURL + "\"}");
            request.setEntity(requestEntity);

            HttpResponse response = httpClient.execute(request);
            HttpEntity entity = response.getEntity();

            if (entity != null) {
                // Format and display the JSON response.
                String jsonString = EntityUtils.toString(entity);
                JSONObject json = new JSONObject(jsonString);
                System.out.println("REST Response:\n");
                System.out.println(json.toString(2));
                JSONArray regions = (JSONArray) json.get("regions");
                if (regions.length() == 0) {
                    System.out.println("No number extracted, Moving to manual validation");
                    moveToErrorHandling = true;
                }
            }
        }catch (Exception e) {
            moveToErrorHandling = true;
        }
    }
}
