import 'dart:convert';
import 'dart:core';

class BleData {
  var type = "W";
  var field = "E";
  var data = SendData();

  Map<String, dynamic> toJson() {
    return {"type": type, "field": field, "data": data};
  }

  String convert() {
    return "+${jsonEncode(toJson())}*";
  }

  BleData({String? type, String? field, SendData? data}) {
    this.type = type ?? "W";
    this.field = field ?? "E";
    this.data = data ?? SendData();
  }
}

class SendData {
  var objectSend = "all";
  var value = "0";

  SendData({String? objectSend, String? value}) {
    this.objectSend = objectSend ?? "all";
    this.value = value ?? "0";
  }

  Map<String, dynamic> toJson() {
    return {"object": objectSend, "value": value};
  }
}
