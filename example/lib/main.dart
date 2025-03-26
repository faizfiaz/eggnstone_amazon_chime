import 'dart:convert';
import 'dart:io';

import 'package:chime_example/MeetingSessionCreator.dart';
import 'package:chime_example/data/Attendee.dart';
import 'package:chime_example/data/Attendees.dart';
import 'package:device_info/device_info.dart';
import 'package:eggnstone_amazon_chime/eggnstone_amazon_chime.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:webview_flutter/webview_flutter.dart';

void main() {
  runApp(App());
}

class App extends StatefulWidget {
  @override
  _AppState createState() => _AppState();
}

class _AppState extends State<App> {
  String _version = 'Unknown';
  String _createMeetingSessionResult = 'CreateMeetingSession: Unknown';
  String _audioVideoStartResult = 'AudioVideo: Unknown';
  String _audioVideoStartLocalVideoResult = 'AudioVideoLocalVideo: Unknown';
  String _audioVideoStartRemoteVideoResult = 'AudioVideoRemoteVideo: Unknown';

  Attendees _attendees = Attendees();
  bool _isAndroidEmulator = false;
  bool _isIosSimulator = false;

  bool isWebviewActivated = false;
  late WebViewController controller;
  late FlutterTts flutterTts;

  @override
  void initState() {
    super.initState();

    _requestPermission();
    initTts();

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _startChime();
      await _audioVideoStart();
      await _audioVideoStartLocalVideo();
      await _audioVideoStartRemoteVideo();
    });

    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (int progress) {
            // Update loading bar.
          },
          onPageStarted: (String url) {},
          onPageFinished: (String url) {},
          onWebResourceError: (WebResourceError error) {},
          onNavigationRequest: (NavigationRequest request) {
            if (request.url.startsWith('https://www.youtube.com/')) {
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      )
      ..loadRequest(Uri.parse('https://flutter.dev'));
  }

  dynamic initTts() async {
    flutterTts = FlutterTts();
    flutterTts.setEngine(await flutterTts.getDefaultEngine);
    flutterTts.setVolume(1.0);
    flutterTts.setSpeechRate(0.5);
    flutterTts.setPitch(1.0);
    flutterTts.setLanguage('id-ID');
  }

  Future<void> speak() async {
    await flutterTts.speak(
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.");
  }

  Widget buildVideoAttender() {
    if (_attendees.length > 1) {
      final attendee = _attendees[1];
      if (attendee.videoView != null) {
        return Container(
          width: double.infinity,
          height: double.infinity,
          // color: Colors.black.opacity(0.5),
          child: Center(child: attendee.videoView),
        );
      }
    }

    return const Center(child: Text('Waiting Video Connection'));
  }

  double widthVideoCalle = 130;
  double heightVideoCalle = 170;

  Widget buildVideoOther() {
    if (_attendees.length == 1 || _attendees.length == 2) {
      print('buildVideoOther: _attendees.length == 1');
      final attendee = _attendees[0];
      if (attendee.videoView != null) {
        return Container(
          width: widthVideoCalle,
          height: heightVideoCalle,
          margin: const EdgeInsets.only(left: 24, bottom: 0),
          color: Colors.green,
          alignment: Alignment.center,
          child: attendee.videoView,
        );
      }
    }

    return Container(
      width: widthVideoCalle,
      height: heightVideoCalle,
      color: Colors.grey,
      alignment: Alignment.center,
      child: const Text(
        "Waiting Other User",
        textAlign: TextAlign.center,
        style: TextStyle(
          fontSize: 12,
          color: Colors.white,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
        home: Scaffold(
            appBar: AppBar(
              title: Text('Chime POC'),
              actions: [
                Card(
                  clipBehavior: Clip.antiAlias,
                  color: isWebviewActivated ? Colors.red : Colors.green,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
                  child: InkWell(
                    onTap: () {
                      setState(() {
                        isWebviewActivated = !isWebviewActivated;
                        speak();
                      });
                    },
                    child: Container(
                      width: 45,
                      height: 45,
                      child: Center(child: Icon(Icons.web, color: Colors.white)),
                    ),
                  ),
                )
              ],
            ),
            body: Stack(
              children: [
                buildVideoAttender(),
                Column(mainAxisAlignment: MainAxisAlignment.end, children: [
                  // Text(_createMeetingSessionResult),
                  // Text(_audioVideoStartResult),
                  // Text(_audioVideoStartLocalVideoResult),
                  // Text(_audioVideoStartRemoteVideoResult),
                  Container(
                    width: double.infinity,
                    margin: const EdgeInsets.only(left: 0, bottom: 20, right: 24),
                    alignment: Alignment.bottomLeft,
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        buildVideoOther(),
                        const Spacer(),
                        isWebviewActivated
                            ? Container(
                                width: 200,
                                height: 400,
                                margin: const EdgeInsets.only(left: 12),
                                child: WebViewWidget(controller: controller),
                              )
                            : SizedBox.shrink(),
                      ],
                    ),
                  )
                ])
              ],
            )));

    var chimeViewChildren = List<Widget>.empty(growable: true);

    if (_attendees.length == 0)
      chimeViewChildren.add(Expanded(child: Center(child: Text('No attendees yet.'))));
    else
      for (int attendeeIndex = 0; attendeeIndex < _attendees.length; attendeeIndex++) {
        Attendee attendee = _attendees[attendeeIndex];
        if (attendee.videoView != null)
          chimeViewChildren.add(Expanded(child: Center(child: AspectRatio(aspectRatio: attendee.aspectRatio, child: attendee.videoView))));
      }

    var chimeViewColumn = Column(children: chimeViewChildren);

    Widget content;

    if (_isIosSimulator)
      content = Padding(
          padding: const EdgeInsets.all(16),
          child: Center(
              child: Text(
                  'Chime does not support Android/iOS emulators/simulators.\n\nIf you see the SDK version above then the connection to the SDK works though.')));
    else
      content = Column(children: [
        Text(_createMeetingSessionResult),
        SizedBox(height: 8),
        Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
          Text('Audio/Video:'),
          ElevatedButton(child: Text('Start'), onPressed: () => _audioVideoStart()),
          ElevatedButton(child: Text('Stop'), onPressed: () => _audioVideoStop())
        ]),
        Text(_audioVideoStartResult),
        SizedBox(height: 8),
        Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
          Text('Local Video:'),
          ElevatedButton(child: Text('Start'), onPressed: () => _audioVideoStartLocalVideo()),
          ElevatedButton(child: Text('Stop'), onPressed: () => _audioVideoStopLocalVideo())
        ]),
        Text(_audioVideoStartLocalVideoResult),
        SizedBox(height: 8),
        Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
          Text('Remote Video:'),
          ElevatedButton(child: Text('Start'), onPressed: () => _audioVideoStartRemoteVideo()),
          ElevatedButton(child: Text('Stop'), onPressed: () => _audioVideoStopRemoteVideo())
        ]),
        Text(_audioVideoStartRemoteVideoResult),
        SizedBox(height: 8),
        ElevatedButton(child: Text('Request Permission'), onPressed: () => _requestScreenCapture()),
        ElevatedButton(
            child: Text('Mute'),
            onPressed: () async {
              final result = await Chime.mute();
              print('Mute: $result');
            }),
        ElevatedButton(
            child: Text('unmute'),
            onPressed: () async {
              final result = await Chime.unmute();
              print('unmute: $result');
            }),
        Expanded(child: chimeViewColumn),
      ]);

    return MaterialApp(
        home: Scaffold(
            appBar: AppBar(title: Text('ChimePlugin')),
            body: Column(children: [SizedBox(height: 8), Text(_version), SizedBox(height: 8), Expanded(child: content)])));
  }

  Future<void> _startChime() async {
    await _getVersion();

    if (Platform.isAndroid) {
      DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
      AndroidDeviceInfo androidInfo = await deviceInfo.androidInfo;
      if (androidInfo.isPhysicalDevice) {
        _addListener();
        await _createMeetingSession();
      } else {
        setState(() {
          _isAndroidEmulator = true;
        });
      }
    } else if (Platform.isIOS) {
      DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
      IosDeviceInfo iosInfo = await deviceInfo.iosInfo;
      if (iosInfo.isPhysicalDevice) {
        _addListener();
        await _createMeetingSession();
      } else {
        setState(() {
          _isIosSimulator = true;
        });
      }
    } else {
      _addListener();
      await _createMeetingSession();
    }
  }

  Future<void> _getVersion() async {
    String version;

    try {
      version = await Chime.version ?? '?';
    } on PlatformException {
      version = 'Failed to get version.';
    }

    if (mounted)
      setState(() {
        _version = version;
      });
  }

  void _addListener() {
    Chime.eventChannel.receiveBroadcastStream().listen((data) async {
      dynamic event = JsonDecoder().convert(data);
      String eventName = event['Name'];
      dynamic eventArguments = event['Arguments'];
      switch (eventName) {
        case 'OnVideoTileAdded':
          _handleOnVideoTileAdded(eventArguments);
          break;
        case 'OnVideoTileRemoved':
          _handleOnVideoTileRemoved(eventArguments);
          break;
        default:
          print('Chime.eventChannel.receiveBroadcastStream().listen()/onData()');
          print('Warning: Unhandled event: $eventName');
          print('Data: $data');
          break;
      }
    }, onDone: () {
      print('Chime.eventChannel.receiveBroadcastStream().listen()/onDone()');
    }, onError: (e) {
      print('Chime.eventChannel.receiveBroadcastStream().listen()/onError()');
    });
  }

  Future<void> _createMeetingSession() async {
    if (await Permission.microphone.request().isGranted == false) {
      _createMeetingSessionResult = 'Need microphone permission.';
      return;
    }

    if (await Permission.camera.request().isGranted == false) {
      _createMeetingSessionResult = 'Need camera permission.';
      return;
    }

    String meetingSessionState;

    try {
      // Copy the file MeetingSessionCreator.dart.template to MeetingSessionCreator.dart.
      // Adjust MeetingSessionCreator to supply your proper authenticated meeting data.
      // (You can leave the dummy values but you will not be able to join a real meeting.)
      // This requires you to have an AWS account and Chime being set up there.
      // MeetingSessionCreator.dart is to be ignored by git so that your private data never gets committed.

      // See ChimeServer.js on how to create authenticated meeting data using the AWS SDK.

      meetingSessionState = await MeetingSessionCreator().create() ?? 'OK';
    } on PlatformException catch (e) {
      meetingSessionState = 'Failed to create MeetingSession. PlatformException: $e';
    } catch (e) {
      meetingSessionState = 'Failed to create MeetingSession. Error: $e';
    }

    if (mounted)
      setState(() {
        _createMeetingSessionResult = meetingSessionState;
      });
  }

  Future<void> _audioVideoStart() async {
    String result;

    try {
      result = await Chime.audioVideoStart() ?? 'OK';

      final listDevices = await Chime.listAudioDevices();
      final json = jsonDecode(listDevices.toString());
      await Chime.chooseAudioDevice(json[1]['Label']);
      print('ListAudioDevices: $listDevices');
    } on PlatformException catch (e) {
      result = 'AudioVideoStart failed: PlatformException: $e';
    } catch (e) {
      result = 'AudioVideoStart failed: Error: $e';
    }

    if (mounted)
      setState(() {
        _audioVideoStartResult = result;
      });
  }

  Future<void> _audioVideoStop() async {
    String result;

    try {
      result = await Chime.audioVideoStop() ?? 'OK';
    } on PlatformException catch (e) {
      result = 'AudioVideoStop failed: PlatformException: $e';
    } catch (e) {
      result = 'AudioVideoStop failed: Error: $e';
    }

    if (mounted)
      setState(() {
        _audioVideoStartResult = result;
      });
  }

  Future<void> _audioVideoStartLocalVideo() async {
    String result;

    try {
      result = await Chime.audioVideoStartLocalVideo() ?? 'OK';
    } on PlatformException catch (e) {
      result = 'AudioVideoStartLocalVideo failed: PlatformException: $e';
    } catch (e) {
      result = 'AudioVideoStartLocalVideo failed: Error: $e';
    }

    if (mounted)
      setState(() {
        _audioVideoStartLocalVideoResult = result;
      });
  }

  Future<void> _audioVideoStopLocalVideo() async {
    String result;

    try {
      result = await Chime.audioVideoStopLocalVideo() ?? 'OK';
    } on PlatformException catch (e) {
      result = 'AudioVideoStopLocalVideo failed: PlatformException: $e';
    } catch (e) {
      result = 'AudioVideoStopLocalVideo failed: Error: $e';
    }

    if (mounted)
      setState(() {
        _audioVideoStartLocalVideoResult = result;
      });
  }

  Future<void> _audioVideoStartRemoteVideo() async {
    String result;

    try {
      result = await Chime.audioVideoStartRemoteVideo() ?? 'OK';
    } on PlatformException catch (e) {
      result = 'AudioVideoStartRemoteVideo failed: PlatformException: $e';
    } catch (e) {
      result = 'AudioVideoStartRemoteVideo failed: Error: $e';
    }

    if (mounted)
      setState(() {
        _audioVideoStartRemoteVideoResult = result;
      });
  }

  Future<void> _audioVideoStopRemoteVideo() async {
    String result;

    try {
      result = await Chime.audioVideoStopRemoteVideo() ?? 'OK';
    } on PlatformException catch (e) {
      result = 'AudioVideoStopRemoteVideo failed: PlatformException: $e';
    } catch (e) {
      result = 'AudioVideoStopRemoteVideo failed: Error: $e';
    }

    if (mounted)
      setState(() {
        _audioVideoStartRemoteVideoResult = result;
      });
  }

  void _handleOnVideoTileAdded(dynamic arguments) async {
    bool isLocalTile = arguments['IsLocalTile'];
    int tileId = arguments['TileId'];
    int videoStreamContentHeight = arguments['VideoStreamContentHeight'];
    int videoStreamContentWidth = arguments['VideoStreamContentWidth'];

    print(
        '_handleOnVideoTileAdded: TileId=$tileId, IsLocalTile=$isLocalTile, VideoStreamContentHeight=$videoStreamContentHeight, VideoStreamContentWidth=$videoStreamContentWidth');

    Attendee? attendee = _attendees.getByTileId(tileId);
    if (attendee != null) {
      print(
          '_handleOnVideoTileAdded called but already mapped. TileId=${attendee.tileId}, ViewId=${attendee.viewId}, VideoView=${attendee.videoView}');
      return;
    }

    print('_handleOnVideoTileAdded: New attendee: TileId=$tileId => creating ChimeDefaultVideoRenderView');
    attendee = Attendee(tileId, isLocalTile);
    attendee.height = videoStreamContentHeight;
    attendee.width = videoStreamContentWidth;
    _attendees.add(attendee);

    Attendee nonNullAttendee = attendee;
    setState(() {
      nonNullAttendee.setVideoView(ChimeDefaultVideoRenderView(onPlatformViewCreated: (int viewId) async {
        nonNullAttendee.setViewId(viewId);
        print(
            'ChimeDefaultVideoRenderView created. TileId=${nonNullAttendee.tileId}, ViewId=${nonNullAttendee.viewId}, VideoView=${nonNullAttendee.videoView} => binding');
        await Chime.bindVideoView(nonNullAttendee.viewId!, nonNullAttendee.tileId);
        print(
            'ChimeDefaultVideoRenderView created. TileId=${nonNullAttendee.tileId}, ViewId=${nonNullAttendee.viewId}, VideoView=${nonNullAttendee.videoView} => bound');
      }));
    });
  }

  void _handleOnVideoTileRemoved(dynamic arguments) async {
    int tileId = arguments['TileId'];

    Attendee? attendee = _attendees.getByTileId(tileId);
    if (attendee == null) {
      print('Error: _handleOnVideoTileRemoved: Could not find attendee for TileId=$tileId');
      return;
    }

    print('_handleOnVideoTileRemoved: Found attendee: TileId=${attendee.tileId}, ViewId=${attendee.viewId} => unbinding');
    _attendees.remove(attendee);
    await Chime.unbindVideoView(tileId);
    print('_handleOnVideoTileRemoved: Found attendee: TileId=${attendee.tileId}, ViewId=${attendee.viewId} => unbound');

    setState(() {
      // refresh
    });
  }

  Future<void> _requestScreenCapture() async {
    final result = await Chime.permissionScreenCapture();
    print('RequestScreenCapturePermission: $result');
  }

  void _requestPermission() async {
    final result = await Permission.notification.request();
    print('RequestPermission: $result');
  }

  _showWebViewChime() {}
}
