import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileServerThread extends Thread {

    Socket socket;
    int numOfReplica = 3;

    public FileServerThread(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {

        try (
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream clientData = new DataInputStream(socket.getInputStream())
        ) {

            Daemon.writeLog("FileServerThread established", socket.getRemoteSocketAddress().toString());
            String operation = clientData.readUTF();
            Daemon.writeLog(operation + " request", socket.getRemoteSocketAddress().toString());

            switch (operation) {
                case "put": {

                    int count = 0;
                    // Read filename from clientData.readUTF()
                    String sdfsfilename = clientData.readUTF();

                    Daemon.writeLog(sdfsfilename, "");

                    File sdfsfile = new File("../SDFS/" + sdfsfilename);
                    if (sdfsfile.exists() && System.currentTimeMillis() - sdfsfile.lastModified() < 60000) {
                        out.writeUTF("Confirm");
                        String clientConfirmation = clientData.readUTF();
                        if (clientConfirmation.equals("N"))
                            break;
                    } else {
                        out.writeUTF("Accept");
                    }

                    BufferedOutputStream fileOutputStream = new BufferedOutputStream(
                            new FileOutputStream("../SDFS/" + sdfsfilename));

                    long fileSize = clientData.readLong();

                    Daemon.writeLog("file size", Long.toString(fileSize));

                    byte[] buffer = new byte[Daemon.bufferSize];
                    int bytes;
                    while (fileSize > 0 && (bytes = clientData.read(buffer, 0, (int) Math.min(Daemon.bufferSize, fileSize))) != -1) {
                        fileOutputStream.write(buffer, 0, bytes);
                        fileSize -= bytes;
                    }
                    fileOutputStream.close();

                    File file = new File("../SDFS/" + sdfsfilename);
                    count++;
                    Daemon.writeLog("receive file size", Long.toString(file.length()));

                    // TODO send replica
                    int index = Daemon.neighbors.size() - 1;
                    Thread[] threads = new Thread[2];

                    for (int i = 0; index >= 0 && i < 2; i++) {
                        Socket replicaSocket = new Socket(Daemon.neighbors.get(index).split("#")[1], Daemon.filePortNumber);
                        DataOutputStream outPrint = new DataOutputStream(replicaSocket.getOutputStream());
                        outPrint.writeUTF("replica");
                        outPrint.writeUTF(sdfsfilename);
                        threads[i] = FilesOP.sendFile(file, "../SDFS/" + sdfsfilename, replicaSocket);
                        threads[i].start();
                        index--;
                    }

                    for (Thread t : threads)
                        if (t != null) {
                            t.join();
                            count++;
                            // quorum write
                            if (count >= numOfReplica / 2 || count == Daemon.membershipList.size()) {
                                out.writeUTF("Received");
                            }
                        }

                    break;
                }
                case "replica": {
                    String sdfsfilename = clientData.readUTF();
                    Daemon.writeLog(sdfsfilename, "");

                    BufferedOutputStream fileOutputStream = new BufferedOutputStream(
                            new FileOutputStream("../SDFS/" + sdfsfilename));

                    long fileSize = clientData.readLong();
                    Daemon.writeLog("file size", Long.toString(fileSize));
                    byte[] buffer = new byte[Daemon.bufferSize];
                    int bytes;
                    while (fileSize > 0 && (bytes = clientData.read(buffer, 0, (int) Math.min(Daemon.bufferSize, fileSize))) != -1) {
                        fileOutputStream.write(buffer, 0, bytes);
                        fileSize -= bytes;
                    }
                    fileOutputStream.close();

                    File file = new File("../SDFS/" + sdfsfilename);
                    out.writeUTF("Received");
                    Daemon.writeLog("receive file size", Long.toString(file.length()));
                    System.out.println("Replica File received");
                    System.out.println("Replica File name:" + sdfsfilename);

                    break;
                }
                case "fail replica": {
                    String sdfsfilename = clientData.readUTF();
                    Daemon.writeLog(sdfsfilename, "");

                    if (!new File("../SDFS/" + sdfsfilename).exists()) {
                        out.writeUTF("Ready to receive");
                        BufferedOutputStream fileOutputStream = new BufferedOutputStream(
                                new FileOutputStream("../SDFS/" + sdfsfilename));

                        long fileSize = clientData.readLong();
                        byte[] buffer = new byte[Daemon.bufferSize];
                        int bytes;
                        while (fileSize > 0 && (bytes = clientData.read(buffer, 0, (int) Math.min(Daemon.bufferSize, fileSize))) != -1) {
                            fileOutputStream.write(buffer, 0, bytes);
                            fileSize -= bytes;
                        }
                        fileOutputStream.close();
                        out.writeUTF("Received");
                        Daemon.writeLog("Receive Replica", "");

                    } else {
                        Daemon.writeLog("Replica Exist", "");
                        out.writeUTF("Replica Exist");
                    }
                    break;
                }
                case "get": {
                    String sdfsfilename = clientData.readUTF();
                    Daemon.writeLog(sdfsfilename, "");

                    File file = new File("../SDFS/" + sdfsfilename);
                    if (!file.exists()) {
                        Daemon.writeLog("File Not Exist", "");
                        out.writeUTF("File Not Exist");
                    } else {
                        Daemon.writeLog("File Exist", "");
                        out.writeUTF("File Exist");
                        Thread t = FilesOP.sendFile(file, sdfsfilename, socket);
                        t.start();
                        t.join();
                    }
                    Daemon.writeLog("get complete", sdfsfilename);
                    break;
                }
                case "delete": {
                    String sdfsfilename = clientData.readUTF();
                    Daemon.writeLog(sdfsfilename, "");

                    FilesOP.deleteFile("../SDFS/" + sdfsfilename);

                    // TODO delete replica
                    int index = Daemon.neighbors.size() - 1;
                    for (int i = 0; index >= 0 && i < 2; i++) {
                        Socket replicaSocket = new Socket(Daemon.neighbors.get(index).split("#")[1], Daemon.filePortNumber);
                        DataOutputStream outPrint = new DataOutputStream(replicaSocket.getOutputStream());
                        outPrint.writeUTF("delete replica");
                        outPrint.writeUTF(sdfsfilename);
                        replicaSocket.close();
                        index--;
                    }

                    //out.writeUTF("Delete Success");
                    break;
                }
                case "delete replica": {
                    String sdfsfilename = clientData.readUTF();
                    Daemon.writeLog(sdfsfilename, "");
                    FilesOP.deleteFile("../SDFS/" + sdfsfilename);
                    break;
                }
                case "ls": {
                    String queryResult = "";
                    String sdfsFileName = clientData.readUTF();
                    Daemon.writeLog(sdfsFileName, "");

                    // query the file locally on the coordinator
                    if (new File("../SDFS/" + sdfsFileName).exists()) {
                        queryResult += Daemon.ID.split("#")[1] + "#";
                    }

                    // query the file on the neighbors of the coordinator
                    int j = Daemon.neighbors.size() - 1 ;
                    while (j >= 0) {
                        String tgtNode = Daemon.neighbors.get(j--).split("#")[1];
                        try {
                            Socket lsSocket = new Socket(tgtNode, Daemon.filePortNumber);
                            DataOutputStream lsOut = new DataOutputStream(lsSocket.getOutputStream());
                            DataInputStream lsIn = new DataInputStream(lsSocket.getInputStream());

                            lsOut.writeUTF("ls replica");
                            lsOut.writeUTF(sdfsFileName);

                            lsSocket.setSoTimeout(1000);
                            String result = lsIn.readUTF();
                            if (!result.equals("Empty")) {
                                queryResult += result + "#";
                            }

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    if (queryResult.isEmpty()) {
                        out.writeUTF("Empty");
                    } else {
                        queryResult = queryResult.substring(0, queryResult.length()-1);
                        out.writeUTF(queryResult);
                    }
                    break;
                }
                case "ls replica": {
                    // check if the query file exists on the replica node
                    String sdfsFileName = clientData.readUTF();
                    Daemon.writeLog(sdfsFileName, "");

                    if (new File("../SDFS/" + sdfsFileName).exists()) {
                        out.writeUTF(Daemon.ID.split("#")[1]);
                    } else {
                        out.writeUTF("Empty");
                    }
                    break;
                }
                case "get replica": {
                    String targetNode = clientData.readUTF();
                    List<String> fileList = FilesOP.listFiles("../SDFS/");
                    if (fileList.size() == 0) {
                        out.writeUTF("Empty");
                    } else {
                        for (String file : fileList) {
                            if (targetNode.equals(Hash.getServer(Hash.hashing(file, 8)))) {
                                out.writeUTF(file);
                                Thread t = FilesOP.sendFile(new File("../SDFS/" + file), file, socket);
                                t.start();
                                t.join();
                            }
                        }
                        out.writeUTF("Empty");
                    }
                }
            }


        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
