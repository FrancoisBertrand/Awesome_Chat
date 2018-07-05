package src.com.hoho.android.usbserial.examples.bic.ZnpService.response;


public interface Response {

    ResponseHeader getHeader();
    String getMessageAsString();
    String getMessageAsHex();
}
